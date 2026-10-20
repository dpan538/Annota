"""Reference lookup for the packaged SQLite schema. No HTTP dependencies."""
import argparse
import json
import re
import sqlite3
from pathlib import Path
from build import normalize

ROOT = Path(__file__).resolve().parent


def distance(a, b):
    """Levenshtein plus adjacent transposition, for bounded candidate strings."""
    rows = [list(range(len(b) + 1))]
    for i, char_a in enumerate(a, 1):
        row = [i] + [0] * len(b)
        for j, char_b in enumerate(b, 1):
            row[j] = min(rows[i - 1][j] + 1, row[j - 1] + 1,
                         rows[i - 1][j - 1] + (char_a != char_b))
            if i > 1 and j > 1 and char_a == b[j - 2] and a[i - 2] == char_b:
                row[j] = min(row[j], rows[i - 2][j - 2] + 1)
        rows.append(row)
    return rows[-1][-1]


def lookup(query, limit=12):
    key = normalize(str(query)[:80])
    if not key:
        return {"entries": [], "suggestions": []}
    limit = max(1, min(20, limit))
    chinese = any("\u3400" <= char <= "\u9fff" for char in key)
    path = ROOT / "bundle" / ("zh-en.sqlite" if chinese else "en-zh.sqlite")
    db = sqlite3.connect(f"file:{path}?mode=ro&immutable=1", uri=True)
    db.row_factory = sqlite3.Row
    try:
        if chinese:
            rows = db.execute("SELECT * FROM entries WHERE simplified=? OR traditional=? LIMIT ?",
                              (key, key, limit)).fetchall()
            suggestions = [] if rows else [r[0] for r in db.execute(
                """SELECT DISTINCT simplified FROM entries
                WHERE (simplified>=? AND simplified<?) OR (traditional>=? AND traditional<?)
                ORDER BY length(simplified),simplified LIMIT ?""",
                (key, key + "\U0010ffff", key, key + "\U0010ffff", limit))]
            if not rows and len(suggestions) < limit:
                for r in db.execute("""SELECT DISTINCT simplified FROM entries
                        WHERE instr(simplified,?)>0 OR instr(traditional,?)>0
                        ORDER BY length(simplified),simplified LIMIT ?""", (key, key, limit)):
                    if r[0] not in suggestions:
                        suggestions.append(r[0])
                suggestions = suggestions[:limit]
        else:
            compact = re.sub(r"[^a-z0-9]", "", key)
            rows = db.execute("SELECT * FROM entries WHERE key=? OR (compact=? AND compact!='') LIMIT ?",
                              (key, compact, limit)).fetchall()
            if not rows:
                rows = db.execute("SELECT DISTINCT e.* FROM entries e JOIN forms f ON e.id=f.entry_id WHERE f.key=? LIMIT ?",
                                  (key, limit)).fetchall()
            suggestions = [] if rows or not compact else [r[0] for r in db.execute(
                "SELECT word FROM entries WHERE compact>=? AND compact<? ORDER BY length(compact),compact LIMIT ?",
                (compact, compact + "\U0010ffff", limit))]
            if not rows and len(suggestions) < limit and 3 <= len(compact) <= 40:
                allowance = 1 if len(compact) <= 5 else 2
                head, tail = compact[:2], compact[::-1][:2]
                candidates = db.execute("""SELECT word,compact FROM entries WHERE
                    ((compact>=? AND compact<?) OR (reverse_key>=? AND reverse_key<?))
                    AND length(compact) BETWEEN ? AND ? ORDER BY abs(length(compact)-?),compact LIMIT 5000""",
                    (head, head + "\U0010ffff", tail, tail + "\U0010ffff",
                     len(compact) - allowance, len(compact) + allowance, len(compact))).fetchall()
                ranked = sorted((distance(compact, r["compact"]), r["word"]) for r in candidates)
                for score, word in ranked:
                    if score <= allowance and word not in suggestions:
                        suggestions.append(word)
                suggestions = suggestions[:limit]
        return {"direction": "zh-en" if chinese else "en-zh",
                "entries": [dict(r) for r in rows], "suggestions": suggestions}
    finally:
        db.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("query")
    print(json.dumps(lookup(parser.parse_args().query), ensure_ascii=False, indent=2))
