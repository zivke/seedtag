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

decrypt and verify forgive extra spaces and wrong capitals: a wrong password
is tried again trimmed and with single spaces, then also lower-cased.

Format v1 (all multi-byte fields raw bytes):
    blob   = 0x01 | salt[16] | nonce[12] | AES-256-GCM(entropy) [len(entropy)+16]
    key    = Argon2id(NFKD(password) as UTF-8, salt, t=3, m=65536 KiB, p=1, len=32)
    AAD    = 0x01 (the header byte)
    entropy= raw BIP39 entropy: 16/20/24/28/32 bytes for 12/15/18/21/24 words
    on tag = NDEF Text record, lang "en", UTF-8, standard Base64 with padding
"""
from __future__ import annotations  # list[str], bytes | None: not evaluated, so Python 3.8 works

import base64
import getpass
import hashlib
import hmac
import os
import struct
import sys
import unicodedata

VERSION = 0x01
MAX_FUTURE_VERSION = 0x0F  # a first byte above VERSION up to this is a newer SeedTag format
SALT_LEN, NONCE_LEN, TAG_LEN = 16, 12, 16
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

def entropy_to_words(entropy: bytes) -> list[str]:
    if len(entropy) not in (16, 20, 24, 28, 32):
        raise ValueError("entropy must be 16/20/24/28/32 bytes")
    cs_bits = len(entropy) * 8 // 32
    h = hashlib.sha256(entropy).digest()
    bits = int.from_bytes(entropy, "big") << cs_bits | (h[0] >> (8 - cs_bits))
    n = (len(entropy) * 8 + cs_bits) // 11
    return [WORDLIST[(bits >> (11 * (n - 1 - i))) & 0x7FF] for i in range(n)]


def words_to_entropy(words: list[str]) -> bytes:
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
        raise ValueError("checksum mismatch - check the words")
    return entropy


def normalize_words(s: str) -> list[str]:
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
    return hash_secret_raw(pw, salt, time_cost=ARGON_T, memory_cost=ARGON_M_KIB,
                           parallelism=ARGON_P, hash_len=KEY_LEN, type=Type.ID)


def _argon2_builtin(pw: bytes, salt: bytes) -> bytes:
    print("(no Argon2id package installed: using the slow built-in one, about a minute per try)",
          file=sys.stderr, flush=True)
    return argon2id_builtin(pw, salt, ARGON_T, ARGON_M_KIB, KEY_LEN)


# Tried in this order; all give the same bytes.
KDF_BACKENDS = (("cryptography", _argon2_cryptography), ("argon2-cffi", _argon2_cffi),
                ("built-in", _argon2_builtin))
kdf_used = None  # name of the backend the last derive_key() used, for the CLI to report


def derive_key(password: str, salt: bytes) -> bytes:
    global kdf_used
    pw = unicodedata.normalize("NFKD", password).encode("utf-8")
    why = []
    for name, kdf in KDF_BACKENDS:
        try:
            key = kdf(pw, salt)
        except _Unavailable as e:
            why.append(f"{name}: {e}")
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
    try:
        from cryptography.exceptions import InvalidTag
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ImportError:
        if decrypt_only:
            return AesGcmBuiltin, InvalidTagBuiltin
        raise NoAesGcm("encrypting needs the cryptography package (AES-GCM), which isn't installed. "
                       "Install it: pip install cryptography") from None
    return AESGCM, InvalidTag


def seal(entropy: bytes, password: str, salt: bytes | None = None,
         nonce: bytes | None = None) -> bytes:
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


def open_blob(blob: bytes, password: str) -> bytes:
    if not blob or blob[0] != VERSION:
        if blob and VERSION < blob[0] <= MAX_FUTURE_VERSION:
            raise ValueError(f"this tag was written by a newer version of SeedTag (format {blob[0]}): "
                             "use a current seedtag.py")
        raise ValueError(f"not a SeedTag backup (unknown format {blob[:1].hex() or 'empty'})")
    ent_len = len(blob) - 1 - SALT_LEN - NONCE_LEN - TAG_LEN
    if ent_len not in (16, 20, 24, 28, 32):
        raise ValueError(f"bad blob length {len(blob)}")
    salt = blob[1:1 + SALT_LEN]
    nonce = blob[1 + SALT_LEN:1 + SALT_LEN + NONCE_LEN]
    ct = blob[1 + SALT_LEN + NONCE_LEN:]
    aesgcm, InvalidTag = _aesgcm(decrypt_only=True)
    try:
        return aesgcm(derive_key(password, salt)).decrypt(nonce, ct, blob[:1])
    except InvalidTag:
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


def password_variants(password: str) -> list[tuple[str, str]]:
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


def open_forgiving(blob: bytes, password: str) -> tuple[bytes, str | None]:
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


# -------------------------------------------------------------------- CLI ---

def _read_blob(argv) -> bytes:
    s = argv[0] if argv else input("Blob (Base64): ")
    return base64.b64decode("".join(s.split()), validate=True)


def main(argv):
    global kdf_used
    kdf_used = None
    if not argv or argv[0] not in ("encrypt", "decrypt", "verify"):
        print(__doc__)
        return 2
    cmd, rest = argv[0], argv[1:]
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
            blob = _read_blob(rest)
            entropy, fix = open_forgiving(blob, getpass.getpass("Password: "))
            if fix:
                print(f"note: the password only matched with {FIXES[fix]} ignored; "
                      "check your written copy.", file=sys.stderr)
            if cmd == "decrypt":
                print(" ".join(entropy_to_words(entropy)))
            else:
                print(f"OK  {len(entropy) * 3 // 4} words  fingerprint: {fingerprint(entropy)}")
    except ValueError as e:
        print(f"FAIL: {e}", file=sys.stderr)
        return 1
    finally:
        if kdf_used:
            print(f"(Argon2id from {kdf_used})", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
