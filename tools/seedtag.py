#!/usr/bin/env python3
# SPDX-License-Identifier: 0BSD
# Copyright (C) 2026 zivke
#
# This file alone is under the BSD Zero Clause license, unlike the rest of SeedTag
# (GPL-3.0-or-later), so anyone can always use it to recover a tag, for any purpose:
#
# Permission to use, copy, modify, and/or distribute this software for any
# purpose with or without fee is hereby granted.
#
# THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH
# REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY
# AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,
# INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM
# LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR
# OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR
# PERFORMANCE OF THIS SOFTWARE.
"""
seedtag.py - desktop companion / recovery tool for the SeedTag Android app.

Encrypts a BIP39 mnemonic into the SeedTag v1 blob (the Base64 text stored on
the NFC tag) and decrypts it back. Self-contained: the BIP39 English wordlist
is embedded below. Uses `cryptography` (AES-GCM); Argon2id comes from
`cryptography` >= 44 if it has it, otherwise from `argon2-cffi`. Without
either, decrypt and verify use the built-in Argon2id and AES-GCM below, in
plain Python: same bytes, about a minute per password tried. encrypt needs
`cryptography`. The tool says which Argon2id it used.

Needs Python 3.8 or later. Keep a copy of this file with your written password:
with it and Python, you can recover a tag; the packages only make it faster.

Run it on an offline machine.

    python3 seedtag.py encrypt          # words + password -> Base64 blob
    python3 seedtag.py decrypt [BLOB]   # Base64 blob + password -> words
    python3 seedtag.py verify  [BLOB]   # like decrypt, but only prints OK / FAIL
                                        # plus a short fingerprint, not the words
    python3 seedtag.py check   DUMP     # is anything on the tag besides the backup?

BLOB is the tag's text, or a file that holds it; without it, you're asked
for it. Spaces and line breaks in it, missing = at the end, and the "en"
some NFC apps show in front of it are fine. decrypt and verify also say
whether the text is exactly what SeedTag writes: if you copied it unchanged
from the tag and it isn't, the tag holds something SeedTag doesn't write.
On Windows, type py instead of python3.

DUMP is a full memory dump of the tag: a Flipper Zero .nfc file (NFC Tools
exports one), or the text NFC Tools saves from Read memory (lines like
"[ 04:8D:B4:B5 ] Addr. 00 : DATA"). check needs no password: it checks every page of an NTAG213,
215 or 216 for anything SeedTag doesn't write (data after the backup, other
records, a password-protected area, changed settings) and exits 1 if it finds
any. decrypt and verify also take such a dump in place of BLOB.

Add --builtin to decrypt or verify to use only the built-in Argon2id and
AES-GCM, even when a package is installed. If an installed package fails,
the tool says so and goes on with the next one, down to the built-in code.

decrypt and verify forgive extra spaces and wrong capitals: a wrong password
is tried again trimmed and with single spaces, then also lower-cased.

Format v1 (all multi-byte fields raw bytes):
    blob   = 0x01 | salt[16] | nonce[12] | AES-256-GCM(entropy) [len(entropy)+16]
    key    = Argon2id(NFKD(password) as UTF-8, salt, t=3, m=65536 KiB, p=1, len=32)
    AAD    = 0x01 (the header byte)
    entropy= raw BIP39 entropy: 16/20/24/28/32 bytes for 12/15/18/21/24 words
    on tag = NDEF Text record, lang "en", UTF-8, standard Base64 with padding
"""
# Annotations such as list[str] are in quotes: never evaluated, so Python 3.8 works, with no
# `from __future__` import that a future Python might drop.

import base64
import getpass
import hashlib
import hmac
import os
import re
import struct
import sys
import unicodedata

VERSION = 0x01
MAX_FUTURE_VERSION = 0x0F  # a first byte above VERSION up to this is a newer SeedTag format
SALT_LEN, NONCE_LEN, TAG_LEN = 16, 12, 16
_OVERHEAD = 1 + SALT_LEN + NONCE_LEN + TAG_LEN
ARGON_T, ARGON_M_KIB, ARGON_P, KEY_LEN = 3, 65536, 1, 32

WORDLIST = """
abandon ability able about above absent absorb abstract absurd abuse access
accident account accuse achieve acid acoustic acquire across act action actor
actress actual adapt add addict address adjust admit adult advance advice
aerobic affair afford afraid again age agent agree ahead aim air airport aisle
alarm album alcohol alert alien all alley allow almost alone alpha already also
alter always amateur amazing among amount amused analyst anchor ancient anger
angle angry animal ankle announce annual another answer antenna antique anxiety
any apart apology appear apple approve april arch arctic area arena argue arm
armed armor army around arrange arrest arrive arrow art artefact artist artwork
ask aspect assault asset assist assume asthma athlete atom attack attend
attitude attract auction audit august aunt author auto autumn average avocado
avoid awake aware away awesome awful awkward axis baby bachelor bacon badge bag
balance balcony ball bamboo banana banner bar barely bargain barrel base basic
basket battle beach bean beauty because become beef before begin behave behind
believe below belt bench benefit best betray better between beyond bicycle bid
bike bind biology bird birth bitter black blade blame blanket blast bleak bless
blind blood blossom blouse blue blur blush board boat body boil bomb bone bonus
book boost border boring borrow boss bottom bounce box boy bracket brain brand
brass brave bread breeze brick bridge brief bright bring brisk broccoli broken
bronze broom brother brown brush bubble buddy budget buffalo build bulb bulk
bullet bundle bunker burden burger burst bus business busy butter buyer buzz
cabbage cabin cable cactus cage cake call calm camera camp can canal cancel
candy cannon canoe canvas canyon capable capital captain car carbon card cargo
carpet carry cart case cash casino castle casual cat catalog catch category
cattle caught cause caution cave ceiling celery cement census century cereal
certain chair chalk champion change chaos chapter charge chase chat cheap check
cheese chef cherry chest chicken chief child chimney choice choose chronic
chuckle chunk churn cigar cinnamon circle citizen city civil claim clap clarify
claw clay clean clerk clever click client cliff climb clinic clip clock clog
close cloth cloud clown club clump cluster clutch coach coast coconut code
coffee coil coin collect color column combine come comfort comic common company
concert conduct confirm congress connect consider control convince cook cool
copper copy coral core corn correct cost cotton couch country couple course
cousin cover coyote crack cradle craft cram crane crash crater crawl crazy
cream credit creek crew cricket crime crisp critic crop cross crouch crowd
crucial cruel cruise crumble crunch crush cry crystal cube culture cup cupboard
curious current curtain curve cushion custom cute cycle dad damage damp dance
danger daring dash daughter dawn day deal debate debris decade december decide
decline decorate decrease deer defense define defy degree delay deliver demand
demise denial dentist deny depart depend deposit depth deputy derive describe
desert design desk despair destroy detail detect develop device devote diagram
dial diamond diary dice diesel diet differ digital dignity dilemma dinner
dinosaur direct dirt disagree discover disease dish dismiss disorder display
distance divert divide divorce dizzy doctor document dog doll dolphin domain
donate donkey donor door dose double dove draft dragon drama drastic draw dream
dress drift drill drink drip drive drop drum dry duck dumb dune during dust
dutch duty dwarf dynamic eager eagle early earn earth easily east easy echo
ecology economy edge edit educate effort egg eight either elbow elder electric
elegant element elephant elevator elite else embark embody embrace emerge
emotion employ empower empty enable enact end endless endorse enemy energy
enforce engage engine enhance enjoy enlist enough enrich enroll ensure enter
entire entry envelope episode equal equip era erase erode erosion error erupt
escape essay essence estate eternal ethics evidence evil evoke evolve exact
example excess exchange excite exclude excuse execute exercise exhaust exhibit
exile exist exit exotic expand expect expire explain expose express extend
extra eye eyebrow fabric face faculty fade faint faith fall false fame family
famous fan fancy fantasy farm fashion fat fatal father fatigue fault favorite
feature february federal fee feed feel female fence festival fetch fever few
fiber fiction field figure file film filter final find fine finger finish fire
firm first fiscal fish fit fitness fix flag flame flash flat flavor flee flight
flip float flock floor flower fluid flush fly foam focus fog foil fold follow
food foot force forest forget fork fortune forum forward fossil foster found
fox fragile frame frequent fresh friend fringe frog front frost frown frozen
fruit fuel fun funny furnace fury future gadget gain galaxy gallery game gap
garage garbage garden garlic garment gas gasp gate gather gauge gaze general
genius genre gentle genuine gesture ghost giant gift giggle ginger giraffe girl
give glad glance glare glass glide glimpse globe gloom glory glove glow glue
goat goddess gold good goose gorilla gospel gossip govern gown grab grace grain
grant grape grass gravity great green grid grief grit grocery group grow grunt
guard guess guide guilt guitar gun gym habit hair half hammer hamster hand
happy harbor hard harsh harvest hat have hawk hazard head health heart heavy
hedgehog height hello helmet help hen hero hidden high hill hint hip hire
history hobby hockey hold hole holiday hollow home honey hood hope horn horror
horse hospital host hotel hour hover hub huge human humble humor hundred hungry
hunt hurdle hurry hurt husband hybrid ice icon idea identify idle ignore ill
illegal illness image imitate immense immune impact impose improve impulse inch
include income increase index indicate indoor industry infant inflict inform
inhale inherit initial inject injury inmate inner innocent input inquiry insane
insect inside inspire install intact interest into invest invite involve iron
island isolate issue item ivory jacket jaguar jar jazz jealous jeans jelly
jewel job join joke journey joy judge juice jump jungle junior junk just
kangaroo keen keep ketchup key kick kid kidney kind kingdom kiss kit kitchen
kite kitten kiwi knee knife knock know lab label labor ladder lady lake lamp
language laptop large later latin laugh laundry lava law lawn lawsuit layer
lazy leader leaf learn leave lecture left leg legal legend leisure lemon lend
length lens leopard lesson letter level liar liberty library license life lift
light like limb limit link lion liquid list little live lizard load loan
lobster local lock logic lonely long loop lottery loud lounge love loyal lucky
luggage lumber lunar lunch luxury lyrics machine mad magic magnet maid mail
main major make mammal man manage mandate mango mansion manual maple marble
march margin marine market marriage mask mass master match material math matrix
matter maximum maze meadow mean measure meat mechanic medal media melody melt
member memory mention menu mercy merge merit merry mesh message metal method
middle midnight milk million mimic mind minimum minor minute miracle mirror
misery miss mistake mix mixed mixture mobile model modify mom moment monitor
monkey monster month moon moral more morning mosquito mother motion motor
mountain mouse move movie much muffin mule multiply muscle museum mushroom
music must mutual myself mystery myth naive name napkin narrow nasty nation
nature near neck need negative neglect neither nephew nerve nest net network
neutral never news next nice night noble noise nominee noodle normal north nose
notable note nothing notice novel now nuclear number nurse nut oak obey object
oblige obscure observe obtain obvious occur ocean october odor off offer office
often oil okay old olive olympic omit once one onion online only open opera
opinion oppose option orange orbit orchard order ordinary organ orient original
orphan ostrich other outdoor outer output outside oval oven over own owner
oxygen oyster ozone pact paddle page pair palace palm panda panel panic panther
paper parade parent park parrot party pass patch path patient patrol pattern
pause pave payment peace peanut pear peasant pelican pen penalty pencil people
pepper perfect permit person pet phone photo phrase physical piano picnic
picture piece pig pigeon pill pilot pink pioneer pipe pistol pitch pizza place
planet plastic plate play please pledge pluck plug plunge poem poet point polar
pole police pond pony pool popular portion position possible post potato
pottery poverty powder power practice praise predict prefer prepare present
pretty prevent price pride primary print priority prison private prize problem
process produce profit program project promote proof property prosper protect
proud provide public pudding pull pulp pulse pumpkin punch pupil puppy purchase
purity purpose purse push put puzzle pyramid quality quantum quarter question
quick quit quiz quote rabbit raccoon race rack radar radio rail rain raise
rally ramp ranch random range rapid rare rate rather raven raw razor ready real
reason rebel rebuild recall receive recipe record recycle reduce reflect reform
refuse region regret regular reject relax release relief rely remain remember
remind remove render renew rent reopen repair repeat replace report require
rescue resemble resist resource response result retire retreat return reunion
reveal review reward rhythm rib ribbon rice rich ride ridge rifle right rigid
ring riot ripple risk ritual rival river road roast robot robust rocket romance
roof rookie room rose rotate rough round route royal rubber rude rug rule run
runway rural sad saddle sadness safe sail salad salmon salon salt salute same
sample sand satisfy satoshi sauce sausage save say scale scan scare scatter
scene scheme school science scissors scorpion scout scrap screen script scrub
sea search season seat second secret section security seed seek segment select
sell seminar senior sense sentence series service session settle setup seven
shadow shaft shallow share shed shell sheriff shield shift shine ship shiver
shock shoe shoot shop short shoulder shove shrimp shrug shuffle shy sibling
sick side siege sight sign silent silk silly silver similar simple since sing
siren sister situate six size skate sketch ski skill skin skirt skull slab slam
sleep slender slice slide slight slim slogan slot slow slush small smart smile
smoke smooth snack snake snap sniff snow soap soccer social sock soda soft
solar soldier solid solution solve someone song soon sorry sort soul sound soup
source south space spare spatial spawn speak special speed spell spend sphere
spice spider spike spin spirit split spoil sponsor spoon sport spot spray
spread spring spy square squeeze squirrel stable stadium staff stage stairs
stamp stand start state stay steak steel stem step stereo stick still sting
stock stomach stone stool story stove strategy street strike strong struggle
student stuff stumble style subject submit subway success such sudden suffer
sugar suggest suit summer sun sunny sunset super supply supreme sure surface
surge surprise surround survey suspect sustain swallow swamp swap swarm swear
sweet swift swim swing switch sword symbol symptom syrup system table tackle
tag tail talent talk tank tape target task taste tattoo taxi teach team tell
ten tenant tennis tent term test text thank that theme then theory there they
thing this thought three thrive throw thumb thunder ticket tide tiger tilt
timber time tiny tip tired tissue title toast tobacco today toddler toe
together toilet token tomato tomorrow tone tongue tonight tool tooth top topic
topple torch tornado tortoise toss total tourist toward tower town toy track
trade traffic tragic train transfer trap trash travel tray treat tree trend
trial tribe trick trigger trim trip trophy trouble truck true truly trumpet
trust truth try tube tuition tumble tuna tunnel turkey turn turtle twelve
twenty twice twin twist two type typical ugly umbrella unable unaware uncle
uncover under undo unfair unfold unhappy uniform unique unit universe unknown
unlock until unusual unveil update upgrade uphold upon upper upset urban urge
usage use used useful useless usual utility vacant vacuum vague valid valley
valve van vanish vapor various vast vault vehicle velvet vendor venture venue
verb verify version very vessel veteran viable vibrant vicious victory video
view village vintage violin virtual virus visa visit visual vital vivid vocal
voice void volcano volume vote voyage wage wagon wait walk wall walnut want
warfare warm warrior wash wasp waste water wave way wealth weapon wear weasel
weather web wedding weekend weird welcome west wet whale what wheat wheel when
where whip whisper wide width wife wild will win window wine wing wink winner
winter wire wisdom wise wish witness wolf woman wonder wood wool word work
world worry worth wrap wreck wrestle wrist write wrong yard year yellow you
young youth zebra zero zone zoo
""".split()
assert len(WORDLIST) == 2048
_INDEX = {w: i for i, w in enumerate(WORDLIST)}


# ---------------------------------------------------------------- BIP39 ----

def entropy_to_words(entropy: bytes) -> "list[str]":
    if len(entropy) not in (16, 20, 24, 28, 32):
        raise ValueError("entropy must be 16/20/24/28/32 bytes")
    cs_bits = len(entropy) * 8 // 32
    h = hashlib.sha256(entropy).digest()
    bits = int.from_bytes(entropy, "big") << cs_bits | (h[0] >> (8 - cs_bits))
    n = (len(entropy) * 8 + cs_bits) // 11
    return [WORDLIST[(bits >> (11 * (n - 1 - i))) & 0x7FF] for i in range(n)]


def words_to_entropy(words: "list[str]") -> bytes:
    if len(words) not in (12, 15, 18, 21, 24):
        raise ValueError(f"expected 12/15/18/21/24 words, got {len(words)}")
    bits = 0
    for i, w in enumerate(words, 1):
        if w not in _INDEX:
            # position only: the word itself may be (part of) the seed
            raise ValueError(f"word #{i} is not in the BIP39 English list")
        bits = bits << 11 | _INDEX[w]
    total = len(words) * 11
    cs_bits = total // 33
    ent_bits = total - cs_bits
    entropy = (bits >> cs_bits).to_bytes(ent_bits // 8, "big")
    checksum = bits & ((1 << cs_bits) - 1)
    if hashlib.sha256(entropy).digest()[0] >> (8 - cs_bits) != checksum:
        raise ValueError("checksum mismatch - check the words. If every word is right, this isn't "
                         "a BIP39 phrase (an Electrum phrase, for example).")
    return entropy


def normalize_words(s: str) -> "list[str]":
    return unicodedata.normalize("NFKD", s).lower().split()


# ----------------------------------------------------------------- crypto ---

class NoArgon2(ValueError):
    pass


class _Unavailable(Exception):
    pass


def _argon2_cryptography(pw: bytes, salt: bytes) -> bytes:
    try:
        from cryptography.exceptions import UnsupportedAlgorithm
        from cryptography.hazmat.primitives.kdf.argon2 import Argon2id
    except ImportError:
        raise _Unavailable("cryptography >= 44 isn't installed") from None
    except Exception as e:  # installed, but broken
        raise _Unavailable(f"cryptography doesn't load ({type(e).__name__})") from None
    try:
        return Argon2id(salt=salt, length=KEY_LEN, iterations=ARGON_T,
                        lanes=ARGON_P, memory_cost=ARGON_M_KIB).derive(pw)
    except UnsupportedAlgorithm:
        # cryptography >= 44 built against an OpenSSL without Argon2 (older than 3.2)
        raise _Unavailable("this cryptography build has no Argon2id") from None


def _argon2_cffi(pw: bytes, salt: bytes) -> bytes:
    try:
        from argon2.low_level import Type, hash_secret_raw
    except ImportError:
        raise _Unavailable("argon2-cffi isn't installed") from None
    except Exception as e:  # installed, but broken
        raise _Unavailable(f"argon2-cffi doesn't load ({type(e).__name__})") from None
    return hash_secret_raw(pw, salt, time_cost=ARGON_T, memory_cost=ARGON_M_KIB,
                           parallelism=ARGON_P, hash_len=KEY_LEN, type=Type.ID)


def _argon2_builtin(pw: bytes, salt: bytes) -> bytes:
    _note("using the slow built-in Argon2id: about a minute per try")
    return argon2id_builtin(pw, salt, ARGON_T, ARGON_M_KIB, KEY_LEN)


def _note(msg: str) -> None:
    print(f"({msg})", file=sys.stderr, flush=True)


# Tried in this order; all give the same bytes.
KDF_BACKENDS = (("cryptography", _argon2_cryptography), ("argon2-cffi", _argon2_cffi),
                ("built-in", _argon2_builtin))
kdf_used = None  # name of the backend the last derive_key() used, for the CLI to report
builtin_only = False  # --builtin: only the built-in Argon2id and AES-GCM, no package
_broken = set()  # packages that failed unexpectedly in this run: not tried again


def derive_key(password: str, salt: bytes) -> bytes:
    global kdf_used
    pw = unicodedata.normalize("NFKD", password).encode("utf-8")
    why = []
    for name, kdf in KDF_BACKENDS:
        if name != "built-in" and (builtin_only or name in _broken):
            continue
        try:
            key = kdf(pw, salt)
        except _Unavailable as e:
            why.append(f"{name}: {e}")
            continue
        except Exception as e:
            # A package that changed or broke (a renamed argument, a bad install) must not stop a
            # recovery the built-in code can do. Only the type: the message could echo input.
            if name == "built-in":
                raise
            _broken.add(name)
            _note(f"{name}'s Argon2id failed ({type(e).__name__}): trying the next one")
            why.append(f"{name}: failed ({type(e).__name__})")
            continue
        kdf_used = name
        return key
    raise NoArgon2("no Argon2id available (" + "; ".join(why) + "). "
                   "Install one: pip install 'cryptography>=44' or pip install argon2-cffi")


class NoAesGcm(ValueError):
    pass


def _aesgcm(decrypt_only: bool = False):
    """(AESGCM, InvalidTag) from `cryptography`, checked before the slow key derivation. For
    decrypting, the built-in AES-GCM stands in if it isn't installed; encrypting needs it."""
    if decrypt_only and (builtin_only or "cryptography" in _broken):
        return AesGcmBuiltin, InvalidTagBuiltin
    try:
        from cryptography.exceptions import InvalidTag
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except Exception as e:  # not installed, or installed but broken
        if decrypt_only:
            return AesGcmBuiltin, InvalidTagBuiltin
        what = "isn't installed" if isinstance(e, ImportError) else f"doesn't load ({type(e).__name__})"
        raise NoAesGcm(f"encrypting needs the cryptography package (AES-GCM), which {what}. "
                       "Install it: pip install cryptography") from None
    return AESGCM, InvalidTag


def seal(entropy: bytes, password: str, salt: "bytes | None" = None,
         nonce: "bytes | None" = None) -> bytes:
    if len(entropy) not in (16, 20, 24, 28, 32):
        raise ValueError("bad entropy length")
    salt = salt if salt is not None else os.urandom(SALT_LEN)
    nonce = nonce if nonce is not None else os.urandom(NONCE_LEN)
    header = bytes([VERSION])
    aesgcm, _ = _aesgcm()
    ct = aesgcm(derive_key(password, salt)).encrypt(nonce, entropy, header)
    return header + salt + nonce + ct


class WrongPassword(ValueError):
    pass


_B64_ALPHABET = frozenset("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")
# Characters of a backup's text, = included, for 12, 15, 18, 21 and 24 words.
TEXT_LENGTHS = tuple((1 + SALT_LEN + NONCE_LEN + n + TAG_LEN + 2) // 3 * 4 for n in (16, 20, 24, 28, 32))
_LENGTHS_HINT = (f"A SeedTag backup's text is {', '.join(map(str, TEXT_LENGTHS[:-1]))} or {TEXT_LENGTHS[-1]} "
                 "characters long (12, 15, 18, 21 or 24 words), counting any = at the end.")


def blob_from_text(text: str) -> bytes:
    """The blob in a tag's text, as someone may have copied or typed it: whitespace anywhere (the
    PASSWORD_SPACES), the record's language code "en" in front, URL-safe - and _, and missing or
    extra = at the end are all accepted. Same rules as SeedBox.fromBase64 in the app, checked
    against blob-text.tsv. Raises ValueError with a plain explanation otherwise."""
    s = "".join(c for c in text if c not in PASSWORD_SPACES)
    if s.startswith("enA"):  # every SeedTag text starts with A; "en" is the record's language
        s = s[2:]
    body = s.replace("-", "+").replace("_", "/").rstrip("=")
    for c in body:
        if c == "=":
            raise ValueError("the text has an = before its end: = can only come last. Check what you copied.")
        if c not in _B64_ALPHABET:
            raise ValueError(f"the text contains {c!r}, which can't be in it: only A-Z, a-z, 0-9, + and / "
                             "(and = at the end). Check what you copied.")
    if len(body) % 4 == 1:
        raise ValueError(f"the text is {len(body)} characters long without the = at the end, which can't be "
                         f"right: a character is missing or one too many. {_LENGTHS_HINT}")
    return base64.b64decode(body + "=" * (-len(body) % 4))


def text_is_exact(text: str) -> bool:
    """Whether text is exactly what SeedTag writes for its blob: standard Base64 with its = and
    nothing else. Whatever blob_from_text forgives makes it False, and so do unused bits in the last
    character that aren't zero, which decoders ignore. Same as SeedBox.isExactText in the app,
    checked against blob-text.tsv."""
    try:
        return base64.b64encode(blob_from_text(text)).decode() == text
    except ValueError:
        return False


def text_changes(text: str) -> "list[str]":
    """How a text that blob_from_text accepts differs from what SeedTag writes, in words."""
    canon = base64.b64encode(blob_from_text(text)).decode()
    out = []
    if any(c in PASSWORD_SPACES for c in text):
        out.append("spaces or line breaks")
    s = "".join(c for c in text if c not in PASSWORD_SPACES)
    if s.startswith("enA"):
        out.append("the letters en in front")
        s = s[2:]
    if "-" in s or "_" in s:
        out.append("- or _ in place of + or /")
    body = s.replace("-", "+").replace("_", "/").rstrip("=")
    if len(s) - len(s.rstrip("=")) != len(canon) - len(canon.rstrip("=")):
        out.append("too few or too many = at the end")
    if body != canon.rstrip("="):
        out.append("a last character SeedTag wouldn't write (it decodes to the same bytes)")
    return out


def open_blob(blob: bytes, password: str) -> bytes:
    if not blob or blob[0] != VERSION:
        if blob and VERSION < blob[0] <= MAX_FUTURE_VERSION:
            raise ValueError(f"this tag was written by a newer version of SeedTag (format {blob[0]}): "
                             "use a current seedtag.py. If you typed the text in, check its first "
                             "characters too: a backup from this version starts with A, then one of Q-Z or a-f.")
        raise ValueError(f"not a SeedTag backup (unknown format {blob[:1].hex() or 'empty'}). If you typed "
                         "the text in, check its first characters: a backup starts with A, then one of "
                         "Q-Z or a-f.")
    ent_len = len(blob) - 1 - SALT_LEN - NONCE_LEN - TAG_LEN
    if ent_len not in (16, 20, 24, 28, 32):
        raise ValueError(f"the text is {(len(blob) + 2) // 3 * 4} characters long, counting = at the end, "
                         f"which isn't a backup's length: a character is missing or one too many. {_LENGTHS_HINT}")
    salt = blob[1:1 + SALT_LEN]
    nonce = blob[1 + SALT_LEN:1 + SALT_LEN + NONCE_LEN]
    ct = blob[1 + SALT_LEN + NONCE_LEN:]
    aesgcm, InvalidTag = _aesgcm(decrypt_only=True)
    key = derive_key(password, salt)
    try:
        return aesgcm(key).decrypt(nonce, ct, blob[:1])
    except InvalidTag:
        raise WrongPassword("wrong password or corrupted blob") from None
    except Exception as e:
        if aesgcm is AesGcmBuiltin:
            raise
        # cryptography's AES-GCM failed in some other way: the built-in one can still decrypt.
        _broken.add("cryptography")
        _note(f"cryptography's AES-GCM failed ({type(e).__name__}): using the built-in one")
    try:
        return AesGcmBuiltin(key).decrypt(nonce, ct, blob[:1])
    except InvalidTagBuiltin:
        raise WrongPassword("wrong password or corrupted blob") from None


FIXES = {"Spaces": "extra spaces", "Case": "capital letters", "SpacesAndCase": "extra spaces and capital letters"}


# The whitespace the password rules know, spelled out (not str.split()) so the app can match it
# exactly: str.isspace() as of Python 3.12, Unicode White_Space plus U+001C-U+001F. Same list as
# SeedBox.isPasswordSpace in the app.
PASSWORD_SPACES = frozenset(
    "\t\n\v\f\r\x1c\x1d\x1e\x1f \x85\xa0\u1680" + "".join(map(chr, range(0x2000, 0x200B)))
    + "\u2028\u2029\u202f\u205f\u3000")


def _collapse_spaces(password: str) -> str:
    """Whitespace (PASSWORD_SPACES) trimmed, and every run of it made one plain space."""
    spaced = "".join(" " if c in PASSWORD_SPACES else c for c in password)
    return " ".join(w for w in spaced.split(" ") if w)


def password_variants(password: str) -> "list[tuple[str, str]]":
    """Retry candidates for a password that didn't match: whitespace trimmed and collapsed to
    single spaces, then that lower-cased (char by char, except U+0130, whose lower case is two
    chars). Only variants that differ. Same rules as SeedBox.passwordVariants in the app."""
    collapsed = _collapse_spaces(password)
    lowered = "".join(c if len(c.lower()) != 1 else c.lower() for c in collapsed)
    out = []
    spaces = collapsed != password
    if spaces:
        out.append(("Spaces", collapsed))
    if lowered != collapsed:
        out.append(("SpacesAndCase" if spaces else "Case", lowered))
    return out


def has_stray_spaces(password: str) -> bool:
    """True if the password starts or ends with whitespace, has two in a row, or has whitespace other
    than a plain space. encrypt refuses such a password: a written copy rarely shows it, and the
    forgiving retries only take spaces away. Same rule as Passphrase.hasStraySpaces in the app."""
    return _collapse_spaces(password) != password


def open_forgiving(blob: bytes, password: str) -> "tuple[bytes, str | None]":
    """Like open_blob, but a wrong password is also tried without extra spaces, then lower-cased.
    Returns (entropy, fix), fix being None or a key of FIXES."""
    try:
        return open_blob(blob, password), None
    except WrongPassword as first:
        wrong = first
    for fix, candidate in password_variants(password):
        try:
            return open_blob(blob, candidate), fix
        except WrongPassword:
            pass
    raise wrong


def fingerprint(entropy: bytes) -> str:
    """Short, non-secret-revealing check value (first 4 bytes of SHA-256)."""
    return hashlib.sha256(b"seedtag-fp" + entropy).hexdigest()[:8]


# --------------------------------------------- built-in Argon2id, AES-GCM ---
#
# Last resort, for a machine where neither package can be installed: Argon2id (RFC 9106, one lane,
# no secret or associated data) and AES-256-GCM decryption (FIPS 197, NIST SP 800-38D) in plain
# Python. Decryption only: a tag is written by the app, or by encrypt with the packages installed.
# Slow (about a minute per password tried, and 90 MB of memory) and not constant-time, which is
# no concern for decrypting your own tag on an offline machine. Checked against the same vectors.

_M32, _M64 = 0xFFFFFFFF, 0xFFFFFFFFFFFFFFFF
_BLOCK = struct.Struct("<128Q")  # an Argon2 block: 1024 bytes, 128 little-endian 64-bit words


def _blake2b_long(data: bytes, length: int) -> bytes:
    """H' (RFC 9106 section 3.3): a hash of any length, built from BLAKE2b."""
    data = struct.pack("<I", length) + data
    if length <= 64:
        return hashlib.blake2b(data, digest_size=length).digest()
    r = (length + 31) // 32 - 2
    v = hashlib.blake2b(data, digest_size=64).digest()
    out = [v[:32]]
    for _ in range(r - 1):
        v = hashlib.blake2b(v, digest_size=64).digest()
        out.append(v[:32])
    out.append(hashlib.blake2b(v, digest_size=length - 32 * r).digest())
    return b"".join(out)


def _gb_indexes():
    """The (a, b, c, d) word indexes of GB, in order, for the 8 row and then 8 column permutations
    of the compression function G (RFC 9106 section 3.5), over a block as 128 words."""
    rows = [[16 * i + k for k in range(16)] for i in range(8)]
    cols = [[2 * i + 16 * k + h for k in range(8) for h in (0, 1)] for i in range(8)]
    out = []
    for v in rows + cols:
        out += [(v[0], v[4], v[8], v[12]), (v[1], v[5], v[9], v[13]),
                (v[2], v[6], v[10], v[14]), (v[3], v[7], v[11], v[15]),
                (v[0], v[5], v[10], v[15]), (v[1], v[6], v[11], v[12]),
                (v[2], v[7], v[8], v[13]), (v[3], v[4], v[9], v[14])]
    return out


_GB = _gb_indexes()


def _compress(x: int, y: int) -> int:
    """G(X, Y) on two blocks, each held as one 8192-bit little-endian int."""
    r = x ^ y
    v = list(_BLOCK.unpack(r.to_bytes(1024, "little")))
    for a, b, c, d in _GB:
        va, vb, vc, vd = v[a], v[b], v[c], v[d]
        va = (va + vb + 2 * (va & _M32) * (vb & _M32)) & _M64
        vd ^= va
        vd = (vd >> 32) | ((vd << 32) & _M64)
        vc = (vc + vd + 2 * (vc & _M32) * (vd & _M32)) & _M64
        vb ^= vc
        vb = (vb >> 24) | ((vb << 40) & _M64)
        va = (va + vb + 2 * (va & _M32) * (vb & _M32)) & _M64
        vd ^= va
        vd = (vd >> 16) | ((vd << 48) & _M64)
        vc = (vc + vd + 2 * (vc & _M32) * (vd & _M32)) & _M64
        vb ^= vc
        vb = (vb >> 63) | ((vb << 1) & _M64)
        v[a], v[b], v[c], v[d] = va, vb, vc, vd
    return int.from_bytes(_BLOCK.pack(*v), "little") ^ r


def argon2id_builtin(pw: bytes, salt: bytes, t: int, m_kib: int, length: int) -> bytes:
    """Argon2id v1.3 with parallelism 1, t passes over m_kib KiB, a length-byte tag."""
    p, kind, version = 1, 2, 0x13
    h0 = hashlib.blake2b(
        struct.pack("<6I", p, length, m_kib, t, version, kind)
        + struct.pack("<I", len(pw)) + pw + struct.pack("<I", len(salt)) + salt
        + struct.pack("<II", 0, 0),  # no secret, no associated data
        digest_size=64).digest()
    q = 4 * (m_kib // 4)  # blocks in the one lane
    if q < 8:
        raise ValueError("Argon2id needs at least 8 KiB")
    seg = q // 4
    mem = [0] * q
    for i in (0, 1):
        mem[i] = int.from_bytes(_blake2b_long(h0 + struct.pack("<II", i, 0), 1024), "little")
    for r in range(t):
        for s in range(4):
            independent = r == 0 and s < 2  # Argon2id: data-independent addressing here (3.4.1.2)
            counter = 0
            addresses = ()
            for k in range(2 if r == 0 and s == 0 else 0, seg):
                j = s * seg + k
                prev = mem[j - 1]  # j - 1 is -1 only for j == 0: the last block, as it should be
                if independent:
                    if k % 128 == 0 or not addresses:
                        counter += 1
                        z = int.from_bytes(struct.pack("<7Q", r, 0, s, q, t, kind, counter), "little")
                        addresses = _BLOCK.unpack(_compress(0, _compress(0, z)).to_bytes(1024, "little"))
                    j1 = addresses[k % 128] & _M32
                else:
                    j1 = prev & _M32
                # Which earlier block to mix in (3.4.2), the lane always being this one.
                if r == 0:
                    area, origin = j - 1, 0
                else:
                    area, origin = q - seg + k - 1, (s + 1) * seg % q
                x = (j1 * j1) >> 32
                ref = mem[(origin + area - 1 - ((area * x) >> 32)) % q]
                new = _compress(prev, ref)
                mem[j] = new if r == 0 else mem[j] ^ new
    return _blake2b_long(mem[q - 1].to_bytes(1024, "little"), length)


def _aes_tables():
    exp, log = [0] * 255, [0] * 256  # powers of the generator 3 in GF(2^8)
    a = 1
    for i in range(255):
        exp[i], log[a] = a, i
        a ^= ((a << 1) ^ 0x11B) if a & 0x80 else a << 1
    sbox = []
    for a in range(256):
        b = exp[(255 - log[a]) % 255] if a else 0  # multiplicative inverse
        s = b
        for i in range(1, 5):
            s ^= ((b << i) | (b >> (8 - i))) & 0xFF
        sbox.append(s ^ 0x63)
    return sbox


_SBOX = _aes_tables()


def _xtime(a: int) -> int:
    return ((a << 1) ^ 0x11B) if a & 0x80 else a << 1


def _aes256_round_keys(key: bytes) -> list:
    w = [list(key[i:i + 4]) for i in range(0, 32, 4)]
    rcon = 1
    for i in range(8, 60):
        temp = w[i - 1]
        if i % 8 == 0:
            temp = [_SBOX[b] for b in temp[1:] + temp[:1]]
            temp[0] ^= rcon
            rcon = _xtime(rcon)
        elif i % 8 == 4:
            temp = [_SBOX[b] for b in temp]
        w.append([a ^ b for a, b in zip(w[i - 8], temp)])
    return [sum(w[4 * r:4 * r + 4], []) for r in range(15)]


def _aes_encrypt_block(round_keys: list, block: bytes) -> bytes:
    s = [b ^ k for b, k in zip(block, round_keys[0])]
    for rnd in range(1, 15):
        s = [_SBOX[b] for b in s]
        s = [s[(4 * (c + r) + r) % 16] for c in range(4) for r in range(4)]  # ShiftRows (column-major)
        if rnd < 14:  # MixColumns, not in the last round
            mixed = []
            for c in range(0, 16, 4):
                a0, a1, a2, a3 = s[c:c + 4]
                e = a0 ^ a1 ^ a2 ^ a3
                mixed += [a0 ^ e ^ _xtime(a0 ^ a1), a1 ^ e ^ _xtime(a1 ^ a2),
                          a2 ^ e ^ _xtime(a2 ^ a3), a3 ^ e ^ _xtime(a3 ^ a0)]
            s = mixed
        s = [b ^ k for b, k in zip(s, round_keys[rnd])]
    return bytes(s)


def _ghash(h: int, aad: bytes, ct: bytes) -> int:
    def blocks(data):
        data += bytes(-len(data) % 16)
        return [int.from_bytes(data[i:i + 16], "big") for i in range(0, len(data), 16)]
    y = 0
    for x in blocks(aad) + blocks(ct) + [(8 * len(aad)) << 64 | 8 * len(ct)]:
        x ^= y
        y, v = 0, h  # y = x * h in GF(2^128), bit-reflected (SP 800-38D, algorithm 1)
        for i in range(127, -1, -1):
            if (x >> i) & 1:
                y ^= v
            v = (v >> 1) ^ (0xE1 << 120) if v & 1 else v >> 1
    return y


class InvalidTagBuiltin(Exception):
    pass


class AesGcmBuiltin:
    """AES-256-GCM decryption, 12-byte nonce, 16-byte tag; same call as cryptography's AESGCM."""

    def __init__(self, key: bytes):
        if len(key) != 32:
            raise ValueError("AES-256 needs a 32-byte key")
        self._keys = _aes256_round_keys(key)

    def decrypt(self, nonce: bytes, data: bytes, aad: bytes) -> bytes:
        if len(nonce) != 12 or len(data) < 16:
            raise ValueError("bad nonce or ciphertext length")
        ct, tag = data[:-16], data[-16:]
        h = int.from_bytes(_aes_encrypt_block(self._keys, bytes(16)), "big")
        mask = int.from_bytes(_aes_encrypt_block(self._keys, nonce + b"\0\0\0\1"), "big")
        if not hmac.compare_digest((mask ^ _ghash(h, aad, ct)).to_bytes(16, "big"), tag):
            raise InvalidTagBuiltin()
        out = bytearray()
        for i in range(0, len(ct), 16):
            stream = _aes_encrypt_block(self._keys, nonce + struct.pack(">I", 2 + i // 16))
            out += bytes(a ^ b for a, b in zip(ct[i:i + 16], stream))
        return bytes(out)


# -------------------------------------------------------------- tag dumps ---
#
# `check` reads a full memory dump of the tag and says whether anything is on it besides the backup:
# a hidden copy of the seed could be anywhere a normal NFC app doesn't show (after the message, in the
# lock and settings pages). The dump is the Flipper Zero ".nfc" text file, which NFC Tools and the
# Flipper export, or NFC Tools' plain-text export of Read memory (pages only: no chip version,
# signature or counters). Only NXP NTAG213/215/216 so far: their memory layout is documented and checked here
# page by page.

# Pages in the dump -> chip, its GET_VERSION reply, the capability container's size byte.
_NTAG = {
    45: ("NTAG213", "0004040201000f03", 0x12),
    135: ("NTAG215", "0004040201001103", 0x3E),
    231: ("NTAG216", "0004040201001303", 0x6D),
}


# NFC Tools' text export: "[ 04:8D:B4:B5 ] Addr. 00 : DATA", the page number in hex.
_NFC_TOOLS_LINE = re.compile(r"^\[ (.*?) \] Addr\. ([0-9A-Fa-f]+) : (.*)$")


def is_dump(text: str) -> bool:
    """Whether [text] looks like a memory dump parse_dump reads, not the tag's text."""
    first = text.lstrip().split("\n", 1)[0].strip()
    return first.startswith("Filetype: Flipper NFC device") or bool(_NFC_TOOLS_LINE.match(first))


def _parse_nfc_tools(text: str) -> "tuple[dict, dict]":
    """NFC Tools' text export. A page whose label isn't DATA, or whose bytes aren't 4 bytes of hex, counts
    as not read: nothing here guesses what it held."""
    pages, last = {}, -1
    for line in text.splitlines():
        if not line.strip():
            continue
        m = _NFC_TOOLS_LINE.match(line.strip())
        if not m:
            raise ValueError(f"this line isn't one of NFC Tools' memory lines: {line.strip()!r}")
        n = int(m.group(2), 16)
        last = max(last, n)
        try:
            page = bytes.fromhex(m.group(1).replace(":", " "))
        except ValueError:
            continue
        if len(page) == 4 and m.group(3).strip() == "DATA":
            pages[n] = page
    return {"Pages total": str(last + 1), "Format": "NFC Tools text"}, pages


def parse_dump(text: str) -> "tuple[dict, dict]":
    """The header fields and the pages ({number: 4 bytes}) of a Flipper .nfc dump, or of NFC Tools' text
    export (no header there: only the page count). Raises ValueError if it's neither."""
    if not text.lstrip().startswith("Filetype:") and is_dump(text):
        return _parse_nfc_tools(text)
    fields, pages = {}, {}
    for line in text.splitlines():
        key, sep, value = line.partition(":")
        if not sep:
            continue
        key, value = key.strip(), value.strip()
        if key.startswith("Page ") and key[5:].isdigit():
            try:
                page = bytes.fromhex(value)
            except ValueError:
                raise ValueError(f"{key} isn't 4 bytes of hex: {value!r}")
            if len(page) != 4:
                raise ValueError(f"{key} isn't 4 bytes of hex: {value!r}")
            pages[int(key[5:])] = page
        else:
            fields[key] = value
    if fields.get("Filetype") != "Flipper NFC device" or not pages:
        raise ValueError("this isn't a memory dump: a Flipper .nfc file (NFC Tools and the Flipper Zero export "
                         "one), or the text NFC Tools saves from Read memory.")
    return fields, pages


def _tlvs(data: bytes) -> "tuple[list, int | None]":
    """The TLVs in a Type 2 data area ([data], from page 4) as (type, value) pairs, and where the free
    bytes after the terminator start (None if there's no terminator). Raises ValueError for a length
    that runs past the end."""
    out, i = [], 0
    while i < len(data):
        t = data[i]
        if t == 0x00:
            i += 1
            continue
        if t == 0xFE:
            return out, i + 1
        if i + 1 >= len(data):
            raise ValueError("a TLV is cut off at the end of the data area")
        length, header = data[i + 1], 2
        if length == 0xFF:
            if i + 3 >= len(data):
                raise ValueError("a TLV is cut off at the end of the data area")
            length, header = int.from_bytes(data[i + 2:i + 4], "big"), 4
        if i + header + length > len(data):
            raise ValueError("a TLV runs past the end of the data area")
        out.append((t, data[i + header:i + header + length]))
        i += header + length
    return out, None


def seedtag_message(text: str) -> bytes:
    """The NDEF message SeedTag writes for [text]: one short Text record, UTF-8, language "en"."""
    payload = b"\x02en" + text.encode("utf-8")
    return bytes([0xD1, 1, len(payload)]) + b"T" + payload


_EMPTY_MESSAGES = (b"", bytes([0xD8, 0, 0, 0]), bytes([0xD0, 0, 0]))  # nothing; Android's / the spec's empty record


def check_dump(text: str) -> "tuple[list[tuple[str, str]], str | None]":
    """Checks a dump page by page. Returns (level, message) lines, level OK, NOTE, WARN or FAIL, and
    the backup's text if the tag holds one. Raises ValueError if the dump can't be checked at all."""
    fields, pages = parse_dump(text)
    total = int(fields.get("Pages total", "0") or 0)
    if total not in _NTAG:
        raise ValueError(f"this dump is of a {fields.get('NTAG/Ultralight type') or fields.get('Device type') or 'tag'} "
                         f"with {total or 'an unknown number of'} pages: only NTAG213, NTAG215 and NTAG216 can be "
                         "checked so far.")
    chip, version, cc_size = _NTAG[total]
    out = []

    def ok(m): out.append(("OK", m))
    def note(m): out.append(("NOTE", m))
    def warn(m): out.append(("WARN", m))
    def fail(m): out.append(("FAIL", m))

    missing = [n for n in range(total) if n not in pages]
    if missing:
        fail(f"{len(missing)} of {total} pages weren't read (from page {missing[0]}). A page that won't read can "
             "be behind a password: hold the tag still and dump it again.")
        return out, None
    ok(f"{chip}: all {total} pages read.")
    if fields.get("Format") == "NFC Tools text":
        note(f"This dump holds only the pages, not the chip's version, signature or counters: it can't show "
             f"whether this is a genuine NXP {chip}. NFC Tools' Export .nfc includes them.")
    if fields.get("NTAG/Ultralight type", chip) != chip:
        warn(f"The dump calls the chip {fields['NTAG/Ultralight type']}, but its page count is an {chip}'s.")
    got_version = fields.get("Mifare version", "").replace(" ", "").lower()
    if got_version and got_version != version:
        fail(f"The chip's version ({fields['Mifare version']}) isn't an NXP {chip}'s: it may be a clone, whose "
             "memory can hold more than the pages shown.")

    p0, p1, p2, p3 = (pages[n] for n in range(4))
    if p0[0] != 0x04:
        warn(f"The serial number starts with {p0[0]:02X}, not 04 (NXP's maker code): the chip isn't an NXP "
             f"{chip}. Its memory may hold more than the pages shown, and nobody documents how long it keeps "
             "its data: use it for practice, not for the backup.")
    if p0[3] != 0x88 ^ p0[0] ^ p0[1] ^ p0[2] or p2[0] != p1[0] ^ p1[1] ^ p1[2] ^ p1[3]:
        fail("The serial number's check bytes (pages 0-2) don't match: not what an NXP chip reports.")
    elif not got_version or got_version == version:
        ok("The serial number's check bytes match" + (f", and the chip says it's an NXP {chip}." if got_version else "."))

    dyn, cfg0, cfg1, pwd, pack = (pages[total - k] for k in (5, 4, 3, 2, 1))
    static_lock, dyn_lock = p2[2:4], dyn[0:3]
    if p3[0:3] != bytes([0xE1, 0x10, cc_size]) or p3[3] not in (0x00, 0x0F):
        fail(f"The capability container (page 3) is {p3.hex(' ')}, not E1 10 {cc_size:02X} 00 (or 0F when "
             "read-only).")
    locked = any(static_lock) or any(dyn_lock) or p3[3] == 0x0F
    if not locked:
        ok("Not read-only: no lock bit is set.")
    else:
        warn(f"Lock bits are set (page 2: {static_lock.hex(' ')}, page {total - 5}: {dyn_lock.hex(' ')}, "
             f"capability container {p3[3]:02X}). Expected after Make read-only. If nobody made this tag "
             "read-only, something else set them, and lock bits can carry a few bits of data.")
    if dyn[3] != 0xBD:
        fail(f"Page {total - 5}, byte 3 is {dyn[3]:02X}, not the chip's fixed BD.")
    if cfg0 == bytes([0x04, 0x00, 0x00, 0xFF]) and cfg1 == bytes([0x00, 0x05, 0x00, 0x00]):
        ok("Settings at the factory defaults: no page is behind a password (AUTH0 FF), and the settings "
           "aren't locked.")
    else:
        if cfg0[3] != 0xFF:
            fail(f"From page {cfg0[3]} on, the tag is behind a password (AUTH0 {cfg0[3]:02X}): pages there can "
                 "hold data nobody can read without it. SeedTag never sets one.")
        if cfg0[:3] != bytes([0x04, 0x00, 0x00]) or cfg1 != bytes([0x00, 0x05, 0x00, 0x00]):
            fail(f"The settings (pages {total - 4}-{total - 3}: {cfg0.hex(' ')} / {cfg1.hex(' ')}) aren't the "
                 "factory defaults (04 00 00 FF / 00 05 00 00). SeedTag never changes them.")
    if any(pwd) or any(pack[:2]):
        fail(f"Pages {total - 2}-{total - 1} (password and its reply) don't read as zero, which a real chip "
             "always returns for them.")
    else:
        note(f"Pages {total - 2}-{total - 1} hold the tag's password and its 2-byte reply. They always read as "
             "zero, so no dump shows whether they were changed: 6 bytes nobody can check. With AUTH0 FF they "
             "protect nothing.")
    if any(pack[2:]):
        fail(f"Page {total - 1}, bytes 2-3 aren't zero.")
    counters = {k: v for k, v in fields.items() if k.startswith("Counter ") and v not in ("0", "")}
    if counters:
        fail(f"A counter isn't zero ({', '.join(f'{k}: {v}' for k, v in counters.items())}). SeedTag never uses "
             "the chip's counters, and a counter's value can carry data.")

    data = b"".join(pages[n] for n in range(4, total - 5))
    try:
        tlvs, free = _tlvs(data)
    except ValueError as e:
        fail(f"The data area's structure is broken: {e}.")
        return out, None
    message, others = None, []
    for t, v in tlvs:
        if t == 0x03 and message is None:
            message = v
        elif t == 0x01 and len(v) == 3 and (v[0] >> 4 << (v[2] & 0x0F)) + (v[0] & 0x0F) >= 16 + len(data):
            pass  # Lock Control: where the dynamic lock bytes are, after the data area (NXP's formatting)
        else:
            others.append(t)
    if others:
        fail("The data area holds more than one message: blocks of type "
             f"{', '.join(f'{t:02X}' for t in others)} besides it. SeedTag writes only the message.")
    if free is None:
        fail("The data area has no end marker (FE) after the message.")
    backup = None
    if message is None:
        fail("No NDEF message on the tag.")
    elif message in _EMPTY_MESSAGES:
        ok("Empty: no backup on the tag.")
    else:
        backup = _backup_text(message)
        words = _words_in(backup) if backup is not None and seedtag_message(backup) == message else None
        if words:
            ok(f"The message is exactly a SeedTag backup ({words} words), nothing else.")
        else:
            fail("The message isn't exactly what SeedTag writes (one Text record, language en, the backup's "
                 "Base64 and nothing else). Check it with verify for details.")
    if free is not None:
        rest = data[free:]
        dirty = [i for i, b in enumerate(rest) if b]
        if not dirty:
            ok(f"All {len(rest)} bytes after the message are zero.")
        else:
            first, last = (4 + (free + dirty[0]) // 4), (4 + (free + dirty[-1]) // 4)
            fail(f"{len(dirty)} of the {len(rest)} bytes after the message aren't zero (pages {first}-{last}). "
                 "Anyone can read them with an app that shows the whole memory. If another app, or a SeedTag "
                 "older than the one that clears them, ever wrote this tag, it's what was on it before. If not, "
                 "it's hidden data. Back up with a current SeedTag clears them, and so does Erase.")
    return out, backup


def _words_in(text: str) -> "int | None":
    """The word count of the v1 backup [text] is, exactly as SeedTag writes it; None if it isn't one."""
    if not text_is_exact(text):
        return None
    blob = blob_from_text(text)
    n = len(blob) - _OVERHEAD
    return n * 3 // 4 if blob[:1] == bytes([VERSION]) and n in (16, 20, 24, 28, 32) else None


def dump_text(text: str) -> str:
    """The backup's text from a dump: the first record's, if it's a Text record."""
    _, pages = parse_dump(text)
    data = b"".join(pages[n] for n in sorted(pages) if n >= 4)
    tlvs, _ = _tlvs(data)
    message = next((v for t, v in tlvs if t == 0x03), None)
    backup = _backup_text(message) if message else None
    if backup is None:
        raise ValueError("the dump holds no Text record: the tag is empty, or it isn't a SeedTag backup.")
    return backup


def _backup_text(message: bytes) -> "str | None":
    """The text of [message]'s first record if it's a short UTF-8 Text record, else None."""
    if len(message) < 4 or message[0] & 0x17 != 0x11 or message[1] != 1:  # SR, TNF well-known, type length 1
        return None
    id_len = message[3] if message[0] & 0x08 else 0
    start = 3 + (1 if message[0] & 0x08 else 0)
    if message[start:start + 1] != b"T":
        return None
    payload = message[start + 1 + id_len:start + 1 + id_len + message[2]]
    if not payload or payload[0] & 0x80:
        return None
    try:
        return payload[1 + (payload[0] & 0x3F):].decode("utf-8")
    except UnicodeDecodeError:
        return None


# -------------------------------------------------------------------- CLI ---

def _read_text(argv) -> str:
    """The tag's text from the command line or typed in, or from a file named either way. A file's
    last line break is dropped: editors add one."""
    s = argv[0] if argv else input("Tag text (Base64), or a file that holds it: ")
    if s.strip() and os.path.isfile(s.strip()):
        with open(s.strip(), encoding="utf-8", errors="replace", newline="") as fh:
            s = fh.read()
        if is_dump(s):
            return dump_text(s)  # a memory dump: the backup's text, as the tag holds it
        s = s[:-2] if s.endswith("\r\n") else s[:-1] if s.endswith("\n") else s
    return s


def _read_blob(argv) -> bytes:
    return blob_from_text(_read_text(argv))


def _say_if_exact(text: str) -> None:
    if text_is_exact(text):
        print("The text is exactly what SeedTag writes.", file=sys.stderr)
        return
    changes = text_changes(text)
    print(f"note: the text isn't exactly what SeedTag writes: it has {', '.join(changes[:-1]) + ' and ' if changes[1:] else ''}{changes[-1]}. That's expected "
          "if you typed it, or if the program you copied it with changed it, and the backup still opens. "
          "But if you copied it unchanged from the tag, the tag holds something SeedTag doesn't write: "
          "whatever wrote it may have hidden data in it.", file=sys.stderr)


def _check(argv) -> int:
    if len(argv) != 1 or not os.path.isfile(argv[0]):
        print("check needs a dump file: python3 seedtag.py check <FILE.nfc or FILE.txt>", file=sys.stderr)
        return 2
    try:
        with open(argv[0], encoding="utf-8", errors="replace") as fh:
            lines, backup = check_dump(fh.read())
    except ValueError as e:
        print(f"FAIL: {e}", file=sys.stderr)
        return 1
    for level, message in lines:
        print(f"{level:<5} {message}")
    if any(level == "FAIL" for level, _ in lines):
        print("\nNOT CLEAN: this tag holds more than SeedTag writes, or isn't what it should be (FAIL above).")
        return 1
    print("\nClean: every page holds only what SeedTag writes, or the chip's defaults.")
    if backup:
        print(f"Now check the backup itself: python3 -I -S seedtag.py verify {argv[0]}")
    return 0


def main(argv):
    global kdf_used, builtin_only
    kdf_used = None
    _broken.clear()
    if not argv or argv[0] not in ("encrypt", "decrypt", "verify", "check"):
        print(__doc__)
        return 2
    cmd, rest = argv[0], [a for a in argv[1:] if a != "--builtin"]
    builtin = len(rest) != len(argv) - 1
    if builtin and cmd in ("encrypt", "check"):
        print("--builtin works with decrypt and verify only.", file=sys.stderr)
        return 2
    builtin_only = builtin
    if cmd == "check":
        return _check(rest)
    try:
        if cmd == "encrypt":
            words = normalize_words(getpass.getpass("Mnemonic (hidden): "))
            entropy = words_to_entropy(words)
            pw = getpass.getpass("Password: ")
            if pw != getpass.getpass("Repeat password: "):
                print("Passwords differ.", file=sys.stderr)
                return 1
            if has_stray_spaces(pw):
                print("The password starts or ends with a space, or has two spaces (or a tab) in a row. "
                      "Use single spaces between words only.", file=sys.stderr)
                return 1
            blob = seal(entropy, pw)
            if open_blob(blob, pw) != entropy:  # not assert: survives python -O
                print("Self-check failed: the blob doesn't decrypt.", file=sys.stderr)
                return 1
            print(base64.b64encode(blob).decode())
            print(f"fingerprint: {fingerprint(entropy)}", file=sys.stderr)
        else:
            text = _read_text(rest)
            blob = blob_from_text(text)
            if blob[:1] == bytes([VERSION]) and len(blob) - _OVERHEAD in (16, 20, 24, 28, 32):
                _say_if_exact(text)  # before the password: it's about the text only
            entropy, fix = open_forgiving(blob, getpass.getpass("Password: "))
            if fix:
                print(f"note: the password only matched with {FIXES[fix]} ignored; "
                      "check your written copy.", file=sys.stderr)
            if cmd == "decrypt":
                print(" ".join(entropy_to_words(entropy)))
                print("note: these are only the seed's words. If your wallet also used a passphrase of its own "
                      "(a \"25th word\"), it isn't stored here, and without it the wallet opens empty. A wallet "
                      "can also look empty when the funds are under another account or address type: check "
                      "its settings before you assume anything is lost.", file=sys.stderr)
            else:
                print(f"OK  {len(entropy) * 3 // 4} words  fingerprint: {fingerprint(entropy)}")
                print("(SeedTag's own fingerprint, not the one a wallet shows)", file=sys.stderr)
    except ValueError as e:
        print(f"FAIL: {e}", file=sys.stderr)
        if isinstance(e, WrongPassword):
            print("If you typed the tag's text in, check it as well: one wrong character gives the same "
                  "error as a wrong password.", file=sys.stderr)
        return 1
    except Exception as e:
        # Only the type: the message could echo input. Most likely a broken or changed package.
        print(f"FAIL: unexpected error ({type(e).__name__}).", file=sys.stderr)
        if cmd == "encrypt":
            print("Reinstall the cryptography package (pip install --force-reinstall cryptography) "
                  "and try again.", file=sys.stderr)
        else:
            print(f"To rule out an installed package, run it with Python's standard library only:\n"
                  f"    python3 -I -S seedtag.py {cmd} --builtin", file=sys.stderr)
        return 1
    finally:
        if kdf_used:
            print(f"(Argon2id from {kdf_used})", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
