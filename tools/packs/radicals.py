"""Display glyphs and English names for the 253 RADKFILE radicals.

RADKFILE writes several radicals as a stand-in kanji of the same shape (化 for ⺅ etc.). DISPLAY maps those
to the real radical glyph. Names are our own plain labels (Kangxi-style descriptions), used as radical
keywords on the kanji path; radicals that are themselves kanji default to their first KANJIDIC2 meaning.
"""

import re

# RADKFILE stand-in -> glyph to show.
DISPLAY = {
    "化": "⺅", "个": "𠆢", "并": "丷", "刈": "刂", "込": "⻌", "尚": "⺌", "忙": "忄", "扎": "扌",
    "汁": "氵", "犯": "犭", "艾": "⺾", "邦": "⻏", "阡": "⻖", "老": "⺹", "杰": "灬", "礼": "礻",
    "疔": "疒", "禹": "禸", "初": "衤", "買": "⺲", "滴": "啇",
}

# Names for radicals that are not kanji, stand-ins, or whose dictionary meaning is misleading as a part.
NAMES = {
    "｜": "line", "丶": "dot", "ノ": "slash", "乙": "hook", "亅": "barb", "亠": "lid", "ハ": "eight",
    "マ": "ma", "ユ": "yu", "ヨ": "snout", "化": "person", "个": "person roof", "并": "horns",
    "冂": "upside-down box", "冖": "crown", "冫": "ice", "几": "table", "凵": "container",
    "刈": "knife", "勹": "wrap", "匕": "spoon", "匚": "box", "卜": "divination", "卩": "seal",
    "厂": "cliff", "厶": "private", "込": "movement", "囗": "enclosure", "夂": "winter", "宀": "roof",
    "尚": "small top", "尢": "lame", "尸": "flag", "屮": "sprout", "巛": "river", "已": "snake",
    "幺": "thread", "广": "dotted cliff", "廴": "stride", "廾": "two hands", "弋": "ceremony",
    "彑": "pig's head", "彡": "hair", "彳": "step", "忙": "heart", "扎": "hand", "汁": "water",
    "犯": "dog", "艾": "grass", "邦": "village", "阡": "hill", "老": "old", "杰": "fire",
    "攵": "strike", "无": "not", "曰": "say", "歹": "death", "殳": "weapon", "气": "steam",
    "爻": "mix", "爿": "split wood", "礼": "spirit", "疋": "bolt of cloth", "疔": "sickness",
    "癶": "footsteps", "禹": "track", "初": "clothes", "買": "net", "耒": "plow", "聿": "brush",
    "臼": "mortar", "艮": "stopping", "虍": "tiger", "豕": "pig", "豸": "badger", "釆": "divide",
    "隶": "slave", "隹": "old bird", "髟": "long hair", "鬥": "fight", "鬯": "herbs", "鬲": "tripod",
    "黹": "embroidery", "黽": "frog", "龠": "flute", "滴": "stem", "乞": "beg", "奄": "cover",
    "岡": "hill fort", "巴": "comma", "毋": "do not", "世": "world", "巨": "giant",
}


def display(radical: str) -> str:
    return DISPLAY.get(radical, radical)


def name(radical: str, kanji_meanings: dict[str, list[str]]) -> str:
    if radical in NAMES:
        return NAMES[radical]
    meanings = [re.sub(r"\s*\(?radical.*$", "", m, flags=re.IGNORECASE).strip() for m in kanji_meanings.get(radical) or []]
    meanings = [m for m in meanings if m]
    return meanings[0].lower() if meanings else radical
