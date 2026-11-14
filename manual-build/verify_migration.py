#!/usr/bin/env python3
"""Build-level verification of the Room migrations (round 4: v1 -> v2,
round 5: v2 -> v3, round 5.1: v3 -> v4, Artlog 2.0 minimal v5:
v4 -> v5, next iteration minimal v6: v5 -> v6, final round v6 -> v7:
ADD COLUMN coverPath to music_tracks).

What this checks, without a device or emulator:

1. MIGRATION SQL == Room-generated SQL: every statement in
   ArtworkDatabase.MIGRATION_1_2 and MIGRATION_2_3 (parsed out of the
   Kotlin source, never hard-coded here) must appear,
   whitespace-normalised, in the createAllTables() of the kapt-generated
   ArtworkDatabase_Impl.java. MIGRATION_3_4 rebuilds its tables, so
   only its two `_new` CREATE TABLE statements (with the `_new`
   suffix stripped) and its CREATE INDEX statements are compared
   this way; its INSERT SELECT / DROP / RENAME statements are
   checked by the v3 -> v4 data test instead. MIGRATION_4_5 creates
   `website_favorites` (CREATE TABLE + CREATE INDEX compared this
   way) and DROPs `inspiration_cache` (checked by the v4 -> v5 data
   test instead). MIGRATION_5_6 creates `website_overrides`,
   `directory_categories` and `music_tracks` (CREATE TABLE + CREATE
   INDEX compared this way) and seeds the 5 builtin categories
   (checked by the v5 -> v6 data test instead). MIGRATION_6_7 adds
   the nullable `coverPath` column to `music_tracks` (ALTER TABLE,
   checked by the v6 -> v7 data test instead: the v6-era CREATE TABLE
   in MIGRATION_5_6 no longer matches the v7 generated SQL, so it is
   validated by the v5 -> v6 data test against MIGRATION_5_6 itself).
   If Room's view of the
   schema and the migration disagree, Room throws IllegalStateException
   at open time on the user's phone — this catches that before the APK
   ships.
2. Data preservation: build a v1 database (artworks + artwork_images,
   using the Room-generated v1 statements), insert sample rows, run the
   v1 -> v2 migration SQL, and verify counts AND per-artwork image
   associations are unchanged afterwards. Then build a v2 database
   (the five v2 tables), insert sample rows for every table, run the
   v2 -> v3 migration SQL, and verify all v2 counts are unchanged.
   Then build a v3 database (the seven v3 tables), insert sample
   inspiration rows with a spread of license values, run the v3 -> v4
   migration SQL, and verify: counts unchanged; rows whose own license
   field proves a CC / public-domain license become IMAGE / ALLOWED
   with their image fields kept; rows with a null / blank / unrelated
   license become LINK / UNKNOWN with image fields cleared but
   sourceUrl, time and note kept; stored category strings are kept
   verbatim. Then build a v4 database (the seven v4 tables), insert
   sample rows for every table, run the v4 -> v5 migration SQL, and
   verify: all non-cache counts unchanged; every old favorite row
   (including the user's note) survives byte-for-byte; the
   `inspiration_cache` table (and its indexes) no longer exists; the
   new `website_favorites` table accepts inserts and reads. Then build
   a v5 database (the seven v5 tables), insert sample rows for every
   table, run the v5 -> v6 migration SQL, and verify: all v5 counts
   unchanged; every old favorite row (including the user's note)
   survives byte-for-byte; the three new tables exist (overrides and
   tracks start empty, categories hold exactly the 5 builtin seed
   rows in order); the new tables accept inserts and reads. Then
   build a v6 database (the v6 `music_tracks` table from
   MIGRATION_5_6 plus the other v6 tables from the generated SQL),
   insert sample tracks, run the v6 -> v7 migration SQL, and verify:
   all counts unchanged; every old track row survives with
   `coverPath` NULL; the new column is a nullable TEXT column; the
   stored schema of `music_tracks` equals the Room-generated v7 SQL;
   a new track row with a `coverPath` inserts and reads back.
3. Post-migration schema: the new tables' columns, primary keys,
   foreign keys and indexes (via PRAGMA) match the entity definitions.
4. Fresh install: createAllTables covers all ten tables (v6 schema).

Usage:
    python3 verify_migration.py \
        --impl manual-build/build/kapt/com/artlog/app/data/ArtworkDatabase_Impl.java \
        --source app/src/main/java/com/artlog/app/data/ArtworkDatabase.kt

Exit code 0 = all checks pass, 1 = any check fails.
"""

import argparse
import re
import sqlite3
import sys
import tempfile
import os


def fail(msg):
    print(f"FAIL: {msg}")
    return False


def ok(msg):
    print(f"PASS: {msg}")
    return True


def normalize(sql):
    """Collapse all whitespace so formatting differences don't matter.
    Also strip whitespace around commas and parentheses: Room's
    generated SQL writes `CASCADE ,` and `SET NULL )` where the
    migration writes `CASCADE,` and `SET NULL)` — SQLite treats them
    identically, and Room normalises the same way when it compares
    schemas (this is the whitespace-only difference the round-3 build
    already recorded)."""
    s = re.sub(r"\s+", " ", sql).strip()
    s = re.sub(r"\s+,", ",", s)
    s = re.sub(r"\(\s+", "(", s)
    s = re.sub(r"\s+\)", ")", s)
    return s


def extract_kotlin_string_concat(block):
    """Join the \"...\" + \"...\" pieces of one execSQL(...) argument."""
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', block)
    return "".join(p.replace('\\"', '"') for p in parts)


def parse_migration_sql(source_path, name):
    """Return the list of SQL statements in the named MIGRATION, in order.
    The block is bounded by the next `val MIGRATION_` declaration (or
    end of file), so a later migration's statements are never mixed in."""
    with open(source_path, encoding="utf-8") as f:
        src = f.read()
    start = src.index(f"val {name}")
    end = src.find("val MIGRATION_", start + 1)
    block = src[start:] if end == -1 else src[start:end]
    statements = []
    for m in re.finditer(r"db\.execSQL\(\s*((?:\"(?:[^\"\\]|\\.)*\"\s*\+?\s*)+)\)",
                         block, re.DOTALL):
        statements.append(extract_kotlin_string_concat(m.group(1)))
    if not statements:
        raise SystemExit(f"could not parse {name} from " + source_path)
    return statements


def parse_generated_sql(impl_path):
    """Return the execSQL statements in createAllTables(), in order."""
    with open(impl_path, encoding="utf-8") as f:
        src = f.read()
    start = src.index("createAllTables")
    block = src[start:]
    statements = re.findall(r'db\.execSQL\("((?:[^"\\]|\\.)*)"\);', block)
    if not statements:
        raise SystemExit("could not parse createAllTables from " + impl_path)
    return [s.replace('\\"', '"') for s in statements]


def table_columns(conn, table):
    rows = conn.execute(f"PRAGMA table_info(`{table}`)").fetchall()
    # (cid, name, type, notnull, dflt_value, pk)
    return [(r[1], r[2], r[3], r[5]) for r in rows]


def table_fks(conn, table):
    rows = conn.execute(f"PRAGMA foreign_key_list(`{table}`)").fetchall()
    # (id, seq, table, from, to, on_update, on_delete, match)
    return sorted([(r[2], r[3], r[4], r[5], r[6]) for r in rows])


def table_indexes(conn, table):
    rows = conn.execute(f"PRAGMA index_list(`{table}`)").fetchall()
    # (seq, name, unique, origin, partial)
    out = []
    for r in rows:
        # sqlite_autoindex_* are SQLite-internal unique indexes created
        # for PRIMARY KEY / UNIQUE constraints; they exist in every
        # SQLite database with such constraints and are not Room indexes.
        if r[1].startswith("sqlite_autoindex"):
            continue
        cols = conn.execute(f"PRAGMA index_info(`{r[1]}`)").fetchall()
        out.append((r[1], r[2], tuple(c[2] for c in cols)))
    return sorted(out)


def check_schema(conn, generated_sql, tables, expected_cols, expected_fks,
                 expected_indexes):
    """PRAGMA checks for a set of tables. Returns False on any mismatch."""
    passed = True
    for table, cols in expected_cols.items():
        actual = table_columns(conn, table)
        if actual == cols:
            passed &= ok(f"schema columns of `{table}` match entities")
        else:
            passed &= fail(f"schema columns of `{table}` differ: "
                           f"expected {cols}, got {actual}")
    for table, fks in expected_fks.items():
        actual = table_fks(conn, table)
        if actual == sorted(fks):
            passed &= ok(f"foreign keys of `{table}` match entities")
        else:
            passed &= fail(f"foreign keys of `{table}` differ: "
                           f"expected {sorted(fks)}, got {actual}")
    for table, idxs in expected_indexes.items():
        actual = table_indexes(conn, table)
        if actual == sorted(idxs):
            passed &= ok(f"indexes of `{table}` match entities")
        else:
            passed &= fail(f"indexes of `{table}` differ: "
                           f"expected {sorted(idxs)}, got {actual}")
    # Post-migration sqlite_master SQL == Room-generated SQL.
    # SQLite drops the "IF NOT EXISTS" clause when it stores the
    # schema, so strip it from the generated side before comparing.
    # SQLite also rewrites the table name in double quotes when a
    # table was renamed (ALTER TABLE RENAME, as MIGRATION_3_4 does),
    # while Room generates backticks — normalize that too. The
    # comparison is about columns and keys, not quote style.
    for table in tables:
        row = conn.execute(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name=?",
            (table,)).fetchone()
        actual = normalize(row[0]) if row else ""
        actual = actual.replace(f'"{table}"', f"`{table}`")
        expected = next(
            (normalize(s).replace("IF NOT EXISTS ", "")
             for s in generated_sql
             if normalize(s).startswith(
                 f"CREATE TABLE IF NOT EXISTS `{table}`")),
            None)
        if expected is not None and actual == expected:
            passed &= ok(f"stored schema of `{table}` == Room-generated SQL")
        else:
            passed &= fail(f"stored schema of `{table}` differs from "
                           f"Room-generated SQL")
    return passed


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--impl", required=True,
                        help="kapt-generated ArtworkDatabase_Impl.java")
    parser.add_argument("--source", required=True,
                        help="ArtworkDatabase.kt")
    args = parser.parse_args()

    passed = True
    migration_1_2 = parse_migration_sql(args.source, "MIGRATION_1_2")
    migration_2_3 = parse_migration_sql(args.source, "MIGRATION_2_3")
    migration_3_4 = parse_migration_sql(args.source, "MIGRATION_3_4")
    migration_4_5 = parse_migration_sql(args.source, "MIGRATION_4_5")
    migration_5_6 = parse_migration_sql(args.source, "MIGRATION_5_6")
    migration_6_7 = parse_migration_sql(args.source, "MIGRATION_6_7")
    generated_sql = parse_generated_sql(args.impl)
    gen_norm = {normalize(s): s for s in generated_sql}

    # --- Check 1: every migration statement matches Room-generated SQL ---
    print(f"MIGRATION_1_2 statements: {len(migration_1_2)}, "
          f"MIGRATION_2_3 statements: {len(migration_2_3)}, "
          f"MIGRATION_3_4 statements: {len(migration_3_4)}, "
          f"MIGRATION_4_5 statements: {len(migration_4_5)}, "
          f"MIGRATION_5_6 statements: {len(migration_5_6)}, "
          f"MIGRATION_6_7 statements: {len(migration_6_7)}, "
          f"generated statements: {len(generated_sql)}")
    for stmt in migration_1_2 + migration_2_3:
        norm = normalize(stmt)
        # migration_2_3's two inspiration CREATE TABLEs are v3-era:
        # the current (v5) generated SQL has the new columns and a
        # nullable imageUrl, so they cannot match it. They are
        # validated instead by the v2->v3 data test below (PRAGMA
        # columns + stored schema compared against migration_2_3
        # itself), which is the same coverage Check 1 gives the
        # other tables. The same holds for migration_2_3's two
        # `inspiration_cache` CREATE INDEX statements: MIGRATION_4_5
        # dropped the cache table, so the current generated SQL has
        # no cache indexes at all; the v2->v3 data test validates
        # them against migration_2_3 itself.
        if norm.startswith("CREATE TABLE IF NOT EXISTS `inspiration_"):
            passed &= ok(f"migration 2->3 CREATE TABLE is v3-era "
                         f"(checked by v2->v3 data test): {norm[:80]}...")
            continue
        if norm.startswith("CREATE INDEX IF NOT EXISTS `index_inspiration_cache"):
            passed &= ok(f"migration 2->3 cache CREATE INDEX is v3-era "
                         f"(checked by v2->v3 data test): {norm[:80]}...")
            continue
        if norm in gen_norm:
            passed &= ok(f"migration SQL matches generated SQL: "
                         f"{norm[:80]}...")
        else:
            passed &= fail(f"migration SQL NOT in generated SQL: {stmt[:120]}")
    # MIGRATION_3_4 rebuilds its tables: only the two `_new` CREATE
    # TABLE statements (table name renamed back) and the CREATE INDEX
    # statements can match createAllTables; the INSERT SELECT / DROP /
    # RENAME steps are verified by the v3 -> v4 data test below.
    # The cache table's statements are v4-era: MIGRATION_4_5 dropped
    # the cache table, so the current (v5) generated SQL has no cache
    # table or cache indexes; they are validated instead by the
    # v3 -> v4 data test (PRAGMA + stored schema compared against
    # migration_3_4 itself).
    for stmt in migration_3_4:
        norm = normalize(stmt)
        if ("inspiration_cache" in norm):
            passed &= ok(f"migration 3->4 cache statement is v4-era "
                         f"(checked by v3->v4 data test): {norm[:80]}...")
            continue
        if norm.startswith("CREATE TABLE IF NOT EXISTS `inspiration_"):
            renamed = norm.replace("`inspiration_cache_new`", "`inspiration_cache`")
            renamed = renamed.replace("`inspiration_favorites_new`",
                                      "`inspiration_favorites`")
            if renamed in gen_norm:
                passed &= ok(f"migration 3->4 CREATE TABLE matches generated SQL: "
                             f"{renamed[:80]}...")
            else:
                passed &= fail(f"migration 3->4 CREATE TABLE NOT in generated SQL: "
                               f"{stmt[:120]}")
        elif norm.startswith("CREATE INDEX"):
            if norm in gen_norm:
                passed &= ok(f"migration 3->4 CREATE INDEX matches generated SQL: "
                             f"{norm[:80]}...")
            else:
                passed &= fail(f"migration 3->4 CREATE INDEX NOT in generated SQL: "
                               f"{stmt[:120]}")
        else:
            passed &= ok(f"migration 3->4 rebuild step (checked by data test): "
                         f"{norm[:80]}...")
    # MIGRATION_4_5 creates website_favorites (CREATE TABLE + CREATE
    # INDEX compared against createAllTables) and DROPs
    # inspiration_cache (verified by the v4 -> v5 data test below).
    for stmt in migration_4_5:
        norm = normalize(stmt)
        if norm.startswith("CREATE TABLE IF NOT EXISTS `website_favorites`"):
            if norm in gen_norm:
                passed &= ok(f"migration 4->5 CREATE TABLE matches generated SQL: "
                             f"{norm[:80]}...")
            else:
                passed &= fail(f"migration 4->5 CREATE TABLE NOT in generated SQL: "
                               f"{stmt[:120]}")
        elif norm.startswith("CREATE INDEX"):
            if norm in gen_norm:
                passed &= ok(f"migration 4->5 CREATE INDEX matches generated SQL: "
                             f"{norm[:80]}...")
            else:
                passed &= fail(f"migration 4->5 CREATE INDEX NOT in generated SQL: "
                               f"{stmt[:120]}")
        elif norm.startswith("DROP TABLE"):
            passed &= ok(f"migration 4->5 DROP TABLE (checked by v4->v5 data test): "
                         f"{norm[:80]}...")
        else:
            passed &= fail(f"migration 4->5 unexpected statement: {stmt[:120]}")
    # MIGRATION_5_6 creates the three v6 tables (CREATE TABLE + CREATE
    # INDEX compared against createAllTables) and seeds the 5 builtin
    # categories (INSERT OR IGNORE, verified by the v5 -> v6 data test).
    # Exception: the v6-era `music_tracks` CREATE TABLE no longer
    # matches the v7 generated SQL (v7 adds the nullable `coverPath`
    # column, last); it is validated instead by the v5 -> v6 data test
    # (PRAGMA columns + stored schema compared against MIGRATION_5_6
    # itself), and the v7 shape by the v6 -> v7 data test.
    for stmt in migration_5_6:
        norm = normalize(stmt)
        if norm.startswith("CREATE TABLE IF NOT EXISTS `music_tracks`"):
            passed &= ok(f"migration 5->6 music_tracks CREATE TABLE is v6-era "
                         f"(checked by v5->v6 data test): {norm[:80]}...")
            continue
        if norm.startswith("CREATE TABLE IF NOT EXISTS"):
            if norm in gen_norm:
                passed &= ok(f"migration 5->6 CREATE TABLE matches generated SQL: "
                             f"{norm[:80]}...")
            else:
                passed &= fail(f"migration 5->6 CREATE TABLE NOT in generated SQL: "
                               f"{stmt[:120]}")
        elif norm.startswith("CREATE INDEX"):
            if norm in gen_norm:
                passed &= ok(f"migration 5->6 CREATE INDEX matches generated SQL: "
                             f"{norm[:80]}...")
            else:
                passed &= fail(f"migration 5->6 CREATE INDEX NOT in generated SQL: "
                               f"{stmt[:120]}")
        elif norm.startswith("INSERT OR IGNORE INTO `directory_categories`"):
            passed &= ok(f"migration 5->6 seed INSERT (checked by v5->v6 data test): "
                         f"{norm[:80]}...")
        else:
            passed &= fail(f"migration 5->6 unexpected statement: {stmt[:120]}")
    # MIGRATION_6_7 only adds the nullable `coverPath` column to
    # `music_tracks` (ALTER TABLE — no CREATE TABLE to compare against
    # createAllTables; the post-migration schema is verified by the
    # v6 -> v7 data test below, including stored-schema equality with
    # the Room-generated v7 SQL).
    for stmt in migration_6_7:
        norm = normalize(stmt)
        if norm == ("ALTER TABLE `music_tracks` ADD COLUMN `coverPath` TEXT"):
            passed &= ok(f"migration 6->7 ALTER TABLE (checked by v6->v7 data test): "
                         f"{norm[:80]}...")
        else:
            passed &= fail(f"migration 6->7 unexpected statement: {stmt[:120]}")

    # --- Check 4 (fresh install): all ten tables in createAllTables ---
    for table in ("artworks", "artwork_images", "tags",
                  "artwork_tag_cross_ref", "journal_entries",
                  "inspiration_favorites", "website_favorites",
                  "website_overrides", "directory_categories",
                  "music_tracks"):
        found = any(
            normalize(s).startswith(f"CREATE TABLE IF NOT EXISTS `{table}`")
            for s in generated_sql
        )
        if found:
            passed &= ok(f"fresh-install schema covers table `{table}`")
        else:
            passed &= fail(f"fresh-install schema MISSING table `{table}`")

    # --- Check 2+3 (v1 -> v2): run the migration on a v1 database ---
    v1_tables = [s for s in generated_sql
                 if normalize(s).startswith("CREATE TABLE IF NOT EXISTS `artworks`")
                 or normalize(s).startswith("CREATE TABLE IF NOT EXISTS `artwork_images`")
                 or normalize(s).startswith("CREATE INDEX IF NOT EXISTS `index_artwork_images")]
    with tempfile.TemporaryDirectory() as tmp:
        db_path = os.path.join(tmp, "v1.db")
        conn = sqlite3.connect(db_path)
        try:
            for stmt in v1_tables:
                conn.execute(stmt)
            # Sample v1 data: 2 artworks, 3 images (2 + 1).
            conn.execute(
                "INSERT INTO artworks VALUES "
                "('a1','Sunset',NULL,1700000000000,1700000000000,1700000000000,"
                "45,'COMPLETED','','','','')")
            conn.execute(
                "INSERT INTO artworks VALUES "
                "('a2','Sketch',NULL,1700000000000,1700000000000,1700000000000,"
                "NULL,'DRAFT','','','','')")
            conn.execute(
                "INSERT INTO artwork_images VALUES "
                "('i1','a1','/x/a1/o/1.jpg','/x/a1/t/1.jpg','ORIGINAL',0,"
                "1700000000000,'')")
            conn.execute(
                "INSERT INTO artwork_images VALUES "
                "('i2','a1','/x/a1/o/2.jpg','/x/a1/t/2.jpg','ORIGINAL',1,"
                "1700000000000,'')")
            conn.execute(
                "INSERT INTO artwork_images VALUES "
                "('i3','a2','/x/a2/o/3.jpg','/x/a2/t/3.jpg','ORIGINAL',0,"
                "1700000000000,'')")
            conn.commit()
            before = {
                "artworks": conn.execute("SELECT COUNT(*) FROM artworks").fetchone()[0],
                "images": conn.execute("SELECT COUNT(*) FROM artwork_images").fetchone()[0],
                "a1_images": conn.execute(
                    "SELECT COUNT(*) FROM artwork_images WHERE artworkId='a1'"
                ).fetchone()[0],
                "a2_images": conn.execute(
                    "SELECT COUNT(*) FROM artwork_images WHERE artworkId='a2'"
                ).fetchone()[0],
            }
            for stmt in migration_1_2:
                conn.execute(stmt)
            conn.commit()
            after = {
                "artworks": conn.execute("SELECT COUNT(*) FROM artworks").fetchone()[0],
                "images": conn.execute("SELECT COUNT(*) FROM artwork_images").fetchone()[0],
                "a1_images": conn.execute(
                    "SELECT COUNT(*) FROM artwork_images WHERE artworkId='a1'"
                ).fetchone()[0],
                "a2_images": conn.execute(
                    "SELECT COUNT(*) FROM artwork_images WHERE artworkId='a2'"
                ).fetchone()[0],
                "tags": conn.execute("SELECT COUNT(*) FROM tags").fetchone()[0],
                "xrefs": conn.execute(
                    "SELECT COUNT(*) FROM artwork_tag_cross_ref").fetchone()[0],
                "journal": conn.execute(
                    "SELECT COUNT(*) FROM journal_entries").fetchone()[0],
            }
            for key in ("artworks", "images", "a1_images", "a2_images"):
                if before[key] == after[key]:
                    passed &= ok(f"v1->v2 migration preserves {key}: "
                                 f"{before[key]} -> {after[key]}")
                else:
                    passed &= fail(f"v1->v2 migration CHANGED {key}: "
                                   f"{before[key]} -> {after[key]}")
            if (after["tags"], after["xrefs"], after["journal"]) == (0, 0, 0):
                passed &= ok("v1->v2 new tables start empty (tags/xrefs/journal = 0)")
            else:
                passed &= fail("v1->v2 new tables not empty after migration")

            expected_cols = {
                "tags": [("id", "TEXT", 1, 1), ("name", "TEXT", 1, 0),
                         ("createdAt", "INTEGER", 1, 0)],
                "artwork_tag_cross_ref": [("artworkId", "TEXT", 1, 1),
                                          ("tagId", "TEXT", 1, 2)],
                "journal_entries": [("id", "TEXT", 1, 1), ("title", "TEXT", 1, 0),
                                    ("content", "TEXT", 1, 0),
                                    ("createdAt", "INTEGER", 1, 0),
                                    ("updatedAt", "INTEGER", 1, 0),
                                    ("artworkId", "TEXT", 0, 0)],
            }
            expected_fks = {
                "artwork_tag_cross_ref": [
                    ("artworks", "artworkId", "id", "NO ACTION", "CASCADE"),
                    ("tags", "tagId", "id", "NO ACTION", "CASCADE"),
                ],
                "journal_entries": [
                    ("artworks", "artworkId", "id", "NO ACTION", "SET NULL"),
                ],
            }
            expected_indexes = {
                "tags": [("index_tags_name", 1, ("name",))],
                "artwork_tag_cross_ref": [
                    ("index_artwork_tag_cross_ref_artworkId", 0, ("artworkId",)),
                    ("index_artwork_tag_cross_ref_tagId", 0, ("tagId",)),
                ],
                "journal_entries": [
                    ("index_journal_entries_artworkId", 0, ("artworkId",)),
                ],
            }
            passed &= check_schema(
                conn, generated_sql,
                ("tags", "artwork_tag_cross_ref", "journal_entries"),
                expected_cols, expected_fks, expected_indexes)
        finally:
            conn.close()

    # --- Check 2+3 (v2 -> v3): run the migration on a v2 database ---
    v2_tables = [s for s in generated_sql
                 if normalize(s).startswith("CREATE TABLE IF NOT EXISTS `artworks`")
                 or normalize(s).startswith("CREATE TABLE IF NOT EXISTS `artwork_images`")
                 or normalize(s).startswith("CREATE TABLE IF NOT EXISTS `tags`")
                 or normalize(s).startswith("CREATE TABLE IF NOT EXISTS `artwork_tag_cross_ref`")
                 or normalize(s).startswith("CREATE TABLE IF NOT EXISTS `journal_entries`")
                 or normalize(s).startswith("CREATE INDEX IF NOT EXISTS `index_artwork_images")
                 or normalize(s).startswith("CREATE INDEX IF NOT EXISTS `index_tags")
                 or normalize(s).startswith("CREATE INDEX IF NOT EXISTS `index_artwork_tag_cross_ref")
                 or normalize(s).startswith("CREATE INDEX IF NOT EXISTS `index_journal_entries")]
    with tempfile.TemporaryDirectory() as tmp:
        db_path = os.path.join(tmp, "v2.db")
        conn = sqlite3.connect(db_path)
        try:
            for stmt in v2_tables:
                conn.execute(stmt)
            # Sample v2 data: 2 artworks, 3 images, 1 tag, 1 link, 1 journal entry.
            conn.execute(
                "INSERT INTO artworks VALUES "
                "('a1','Sunset',NULL,1700000000000,1700000000000,1700000000000,"
                "45,'COMPLETED','','','','')")
            conn.execute(
                "INSERT INTO artworks VALUES "
                "('a2','Sketch',NULL,1700000000000,1700000000000,1700000000000,"
                "NULL,'DRAFT','','','','')")
            conn.execute(
                "INSERT INTO artwork_images VALUES "
                "('i1','a1','/x/a1/o/1.jpg','/x/a1/t/1.jpg','ORIGINAL',0,"
                "1700000000000,'')")
            conn.execute(
                "INSERT INTO artwork_images VALUES "
                "('i2','a1','/x/a1/o/2.jpg','/x/a1/t/2.jpg','ORIGINAL',1,"
                "1700000000000,'')")
            conn.execute(
                "INSERT INTO artwork_images VALUES "
                "('i3','a2','/x/a2/o/3.jpg','/x/a2/t/3.jpg','ORIGINAL',0,"
                "1700000000000,'')")
            conn.execute(
                "INSERT INTO tags VALUES ('t1','角色',1700000000000)")
            conn.execute(
                "INSERT INTO artwork_tag_cross_ref VALUES ('a1','t1')")
            conn.execute(
                "INSERT INTO journal_entries VALUES "
                "('j1','手记','内容',1700000000000,1700000000000,'a1')")
            conn.commit()
            before = {
                "artworks": conn.execute("SELECT COUNT(*) FROM artworks").fetchone()[0],
                "images": conn.execute("SELECT COUNT(*) FROM artwork_images").fetchone()[0],
                "a1_images": conn.execute(
                    "SELECT COUNT(*) FROM artwork_images WHERE artworkId='a1'"
                ).fetchone()[0],
                "tags": conn.execute("SELECT COUNT(*) FROM tags").fetchone()[0],
                "xrefs": conn.execute(
                    "SELECT COUNT(*) FROM artwork_tag_cross_ref").fetchone()[0],
                "journal": conn.execute(
                    "SELECT COUNT(*) FROM journal_entries").fetchone()[0],
                "a1_tags": conn.execute(
                    "SELECT COUNT(*) FROM artwork_tag_cross_ref WHERE artworkId='a1'"
                ).fetchone()[0],
                "j1_artwork": conn.execute(
                    "SELECT artworkId FROM journal_entries WHERE id='j1'"
                ).fetchone()[0],
            }
            for stmt in migration_2_3:
                conn.execute(stmt)
            conn.commit()
            after = {
                "artworks": conn.execute("SELECT COUNT(*) FROM artworks").fetchone()[0],
                "images": conn.execute("SELECT COUNT(*) FROM artwork_images").fetchone()[0],
                "a1_images": conn.execute(
                    "SELECT COUNT(*) FROM artwork_images WHERE artworkId='a1'"
                ).fetchone()[0],
                "tags": conn.execute("SELECT COUNT(*) FROM tags").fetchone()[0],
                "xrefs": conn.execute(
                    "SELECT COUNT(*) FROM artwork_tag_cross_ref").fetchone()[0],
                "journal": conn.execute(
                    "SELECT COUNT(*) FROM journal_entries").fetchone()[0],
                "a1_tags": conn.execute(
                    "SELECT COUNT(*) FROM artwork_tag_cross_ref WHERE artworkId='a1'"
                ).fetchone()[0],
                "j1_artwork": conn.execute(
                    "SELECT artworkId FROM journal_entries WHERE id='j1'"
                ).fetchone()[0],
                "cache": conn.execute(
                    "SELECT COUNT(*) FROM inspiration_cache").fetchone()[0],
                "favorites": conn.execute(
                    "SELECT COUNT(*) FROM inspiration_favorites").fetchone()[0],
            }
            for key in ("artworks", "images", "a1_images", "tags", "xrefs",
                        "journal", "a1_tags", "j1_artwork"):
                if before[key] == after[key]:
                    passed &= ok(f"v2->v3 migration preserves {key}: "
                                 f"{before[key]} -> {after[key]}")
                else:
                    passed &= fail(f"v2->v3 migration CHANGED {key}: "
                                   f"{before[key]} -> {after[key]}")
            if (after["cache"], after["favorites"]) == (0, 0):
                passed &= ok("v2->v3 new tables start empty (cache/favorites = 0)")
            else:
                passed &= fail("v2->v3 new tables not empty after migration")

            expected_cols = {
                "inspiration_cache": [
                    ("id", "TEXT", 1, 1), ("category", "TEXT", 1, 2),
                    ("source", "TEXT", 1, 0), ("sourceId", "TEXT", 1, 0),
                    ("title", "TEXT", 1, 0), ("author", "TEXT", 0, 0),
                    ("sourceUrl", "TEXT", 1, 0), ("imageUrl", "TEXT", 1, 0),
                    ("thumbnailUrl", "TEXT", 0, 0), ("width", "INTEGER", 0, 0),
                    ("height", "INTEGER", 0, 0), ("description", "TEXT", 0, 0),
                    ("license", "TEXT", 0, 0), ("licenseUrl", "TEXT", 0, 0),
                    ("dateText", "TEXT", 0, 0), ("place", "TEXT", 0, 0),
                    ("fetchedAt", "INTEGER", 1, 0),
                ],
                "inspiration_favorites": [
                    ("id", "TEXT", 1, 1), ("source", "TEXT", 1, 0),
                    ("sourceId", "TEXT", 1, 0), ("title", "TEXT", 1, 0),
                    ("author", "TEXT", 0, 0), ("sourceUrl", "TEXT", 1, 0),
                    ("imageUrl", "TEXT", 1, 0), ("thumbnailUrl", "TEXT", 0, 0),
                    ("width", "INTEGER", 0, 0), ("height", "INTEGER", 0, 0),
                    ("category", "TEXT", 1, 0), ("description", "TEXT", 0, 0),
                    ("license", "TEXT", 0, 0), ("licenseUrl", "TEXT", 0, 0),
                    ("dateText", "TEXT", 0, 0), ("place", "TEXT", 0, 0),
                    ("savedAt", "INTEGER", 1, 0), ("note", "TEXT", 1, 0),
                ],
            }
            expected_indexes = {
                "inspiration_cache": [
                    ("index_inspiration_cache_category", 0, ("category",)),
                    ("index_inspiration_cache_fetchedAt", 0, ("fetchedAt",)),
                ],
                "inspiration_favorites": [
                    ("index_inspiration_favorites_savedAt", 0, ("savedAt",)),
                ],
            }
            # NOTE: the stored-schema part of check_schema must compare
            # against the v3-era SQL (migration_2_3), not the current
            # v4 generated SQL — the v3 tables genuinely lack the v4
            # columns, and that is expected, not a mismatch.
            passed &= check_schema(
                conn, migration_2_3,
                ("inspiration_cache", "inspiration_favorites"),
                expected_cols, {}, expected_indexes)
        finally:
            conn.close()

    # --- Check 2+3 (v3 -> v4): rebuild + per-record license classification ---
    # v3 database: the five non-inspiration tables come from the
    # generated SQL (unchanged between v3 and v4); the two inspiration
    # tables come from MIGRATION_2_3 (their v3 shape).
    v3_tables = [s for s in generated_sql
                 if normalize(s).startswith("CREATE TABLE IF NOT EXISTS `artworks`")
                 or normalize(s).startswith("CREATE TABLE IF NOT EXISTS `artwork_images`")
                 or normalize(s).startswith("CREATE TABLE IF NOT EXISTS `tags`")
                 or normalize(s).startswith("CREATE TABLE IF NOT EXISTS `artwork_tag_cross_ref`")
                 or normalize(s).startswith("CREATE TABLE IF NOT EXISTS `journal_entries`")
                 or normalize(s).startswith("CREATE INDEX IF NOT EXISTS `index_artwork_images")
                 or normalize(s).startswith("CREATE INDEX IF NOT EXISTS `index_tags")
                 or normalize(s).startswith("CREATE INDEX IF NOT EXISTS `index_artwork_tag_cross_ref")
                 or normalize(s).startswith("CREATE INDEX IF NOT EXISTS `index_journal_entries")]
    v3_inspiration = [s for s in migration_2_3
                      if normalize(s).startswith("CREATE TABLE IF NOT EXISTS `inspiration_")
                      or normalize(s).startswith("CREATE INDEX IF NOT EXISTS `index_inspiration_")]
    with tempfile.TemporaryDirectory() as tmp:
        db_path = os.path.join(tmp, "v3.db")
        conn = sqlite3.connect(db_path)
        try:
            for stmt in v3_tables + v3_inspiration:
                conn.execute(stmt)
            # One artwork / tag / link / journal row: must survive untouched.
            conn.execute(
                "INSERT INTO artworks VALUES "
                "('a1','Sunset',NULL,1700000000000,1700000000000,1700000000000,"
                "45,'COMPLETED','','','','')")
            conn.execute(
                "INSERT INTO artwork_images VALUES "
                "('i1','a1','/x/a1/o/1.jpg','/x/a1/t/1.jpg','ORIGINAL',0,"
                "1700000000000,'')")
            conn.execute(
                "INSERT INTO tags VALUES ('t1','角色',1700000000000)")
            conn.execute(
                "INSERT INTO artwork_tag_cross_ref VALUES ('a1','t1')")
            conn.execute(
                "INSERT INTO journal_entries VALUES "
                "('j1','手记','内容',1700000000000,1700000000000,'a1')")
            # Favorites (v3, 18 columns). License spread:
            # CC BY 2.0 / CC BY-SA 4.0 / CC0 / Public domain / Openverse
            # slug BY-SA / lowercase by-sa -> IMAGE/ALLOWED;
            # NULL / '' / blank / unrelated / GRACG+NULL -> LINK/UNKNOWN.
            fav_cols = ("id, source, sourceId, title, author, sourceUrl, "
                        "imageUrl, thumbnailUrl, width, height, category, "
                        "description, license, licenseUrl, dateText, place, "
                        "savedAt, note")
            fav_rows = [
                ("f1", "OPENVERSE", "111", "晨光", "画师A",
                 "https://example.com/ov/111", "https://img.example.com/111.jpg",
                 "https://img.example.com/111t.jpg", 800, 600, "ILLUSTRATION",
                 "desc", "CC BY 2.0",
                 "https://creativecommons.org/licenses/by/2.0/",
                 "2024", "", 1700000000000, "很喜欢这张"),
                ("f2", "COMMONS", "222", "山", "画师B",
                 "https://commons.wikimedia.org/wiki/File:Mountain.jpg",
                 "https://upload.wikimedia.org/m.jpg",
                 "https://upload.wikimedia.org/mt.jpg", 1024, 768, "LANDSCAPE",
                 None, "CC BY-SA 4.0", None, None, None, 1700000001000, ""),
                ("f3", "MET", "333", "花瓶", None,
                 "https://www.metmuseum.org/art/collection/333",
                 "https://images.metmuseum.org/333.jpg", None, None, None,
                 "OTHER", None, "CC0",
                 "https://creativecommons.org/publicdomain/zero/1.0/",
                 None, None, 1700000002000, ""),
                ("f4", "AIC", "444", "建筑", None,
                 "https://www.artic.edu/artworks/444",
                 "https://www.artic.edu/iiif/2/444.jpg",
                 "https://www.artic.edu/iiif/2/444t.jpg", 843, 600,
                 "ARCHITECTURE", None, "Public domain", None, None, None,
                 1700000003000, "备注四"),
                ("f5", "OPENVERSE", "555", "插画", "画师C",
                 "https://example.com/ov/555", "https://img.example.com/555.jpg",
                 "https://img.example.com/555t.jpg", 640, 480, "ANIME",
                 None, "BY-SA", None, None, None, 1700000004000, ""),
                ("f6", "COMMONS", "666", "小写许可", None,
                 "https://commons.wikimedia.org/wiki/File:Lower.jpg",
                 "https://upload.wikimedia.org/l.jpg", None, None, None,
                 "REALISTIC", None, "by-sa", None, None, None,
                 1700000005000, ""),
                ("f7", "OPENVERSE", "777", "无许可", "画师D",
                 "https://example.com/ov/777", "https://img.example.com/777.jpg",
                 "https://img.example.com/777t.jpg", 640, 480, "ANIME",
                 None, None, None, None, None, 1700000006000, "这条的备注要保留"),
                ("f8", "COMMONS", "888", "空许可", None,
                 "https://commons.wikimedia.org/wiki/File:Empty.jpg",
                 "https://upload.wikimedia.org/e.jpg", None, None, None,
                 "PHOTOGRAPHY", None, "", None, None, None,
                 1700000007000, ""),
                ("f9", "COMMONS", "999", "空白许可", None,
                 "https://commons.wikimedia.org/wiki/File:Blank.jpg",
                 "https://upload.wikimedia.org/b.jpg", None, None, None,
                 "LANDSCAPE", None, "   ", None, None, None,
                 1700000008000, ""),
                ("f10", "OPENVERSE", "1010", "无关许可", "画师E",
                 "https://example.com/ov/1010",
                 "https://img.example.com/1010.jpg", None, None, None,
                 "REALISTIC", None, "All rights reserved", None, None, None,
                 1700000009000, ""),
                ("f11", "SOME_FUTURE_SOURCE", "1111", "未来来源", None,
                 "https://future.example.com/1111",
                 "https://future.example.com/1111.jpg", None, None, None,
                 "OTHER", None, "CC BY 4.0", None, None, None,
                 1700000010000, ""),
                ("f12", "GRACG", "1212", "涂鸦王国链接", None,
                 "https://www.gracg.com/art/1212",
                 "https://img.gracg.com/1212.jpg", None, None, None, "ANIME",
                 None, None, None, None, None, 1700000011000, "等 5.2 再看"),
            ]
            for row in fav_rows:
                conn.execute(
                    f"INSERT INTO inspiration_favorites ({fav_cols}) "
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", row)
            # Cache rows (v3, 17 columns).
            cache_cols = ("id, category, source, sourceId, title, author, "
                          "sourceUrl, imageUrl, thumbnailUrl, width, height, "
                          "description, license, licenseUrl, dateText, place, "
                          "fetchedAt")
            cache_rows = [
                ("c1", "ANIME", "OPENVERSE", "201", "缓存一", "画师F",
                 "https://example.com/ov/201", "https://img.example.com/201.jpg",
                 "https://img.example.com/201t.jpg", 800, 600, None,
                 "CC BY 2.0", None, None, None, 1700000012000),
                ("c2", "LANDSCAPE", "COMMONS", "202", "缓存二", None,
                 "https://commons.wikimedia.org/wiki/File:C2.jpg",
                 "https://upload.wikimedia.org/c2.jpg", None, None, None,
                 None, None, None, None, None, 1700000013000),
                ("c3", "PHOTOGRAPHY", "OPENVERSE", "203", "缓存三", None,
                 "https://example.com/ov/203", "https://img.example.com/203.jpg",
                 None, None, None, None, "BY-NC-SA", None, None, None,
                 1700000014000),
            ]
            for row in cache_rows:
                conn.execute(
                    f"INSERT INTO inspiration_cache ({cache_cols}) "
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", row)
            conn.commit()
            before = {
                "artworks": conn.execute("SELECT COUNT(*) FROM artworks").fetchone()[0],
                "images": conn.execute("SELECT COUNT(*) FROM artwork_images").fetchone()[0],
                "tags": conn.execute("SELECT COUNT(*) FROM tags").fetchone()[0],
                "xrefs": conn.execute(
                    "SELECT COUNT(*) FROM artwork_tag_cross_ref").fetchone()[0],
                "journal": conn.execute(
                    "SELECT COUNT(*) FROM journal_entries").fetchone()[0],
                "favorites": conn.execute(
                    "SELECT COUNT(*) FROM inspiration_favorites").fetchone()[0],
                "cache": conn.execute(
                    "SELECT COUNT(*) FROM inspiration_cache").fetchone()[0],
            }
            for stmt in migration_3_4:
                conn.execute(stmt)
            conn.commit()
            after = {
                "artworks": conn.execute("SELECT COUNT(*) FROM artworks").fetchone()[0],
                "images": conn.execute("SELECT COUNT(*) FROM artwork_images").fetchone()[0],
                "tags": conn.execute("SELECT COUNT(*) FROM tags").fetchone()[0],
                "xrefs": conn.execute(
                    "SELECT COUNT(*) FROM artwork_tag_cross_ref").fetchone()[0],
                "journal": conn.execute(
                    "SELECT COUNT(*) FROM journal_entries").fetchone()[0],
                "favorites": conn.execute(
                    "SELECT COUNT(*) FROM inspiration_favorites").fetchone()[0],
                "cache": conn.execute(
                    "SELECT COUNT(*) FROM inspiration_cache").fetchone()[0],
            }
            for key in before:
                if before[key] == after[key]:
                    passed &= ok(f"v3->v4 migration preserves {key}: "
                                 f"{before[key]} -> {after[key]}")
                else:
                    passed &= fail(f"v3->v4 migration CHANGED {key}: "
                                   f"{before[key]} -> {after[key]}")
            # Per-row classification of favorites.
            fav_after = {
                r[0]: r for r in conn.execute(
                    "SELECT id, contentType, licenseStatus, imageUrl, "
                    "thumbnailUrl, width, height, sourceUrl, note, category, "
                    "savedAt FROM inspiration_favorites").fetchall()
            }
            # (id, contentType, licenseStatus, imageUrl, thumbnailUrl,
            #  width, height, sourceUrl, note, category, savedAt)
            # Thumbnail / width / height are compared against what was
            # inserted (f3, f6, f11 were inserted with nulls there — a
            # kept imageUrl with null thumbnail is still "kept").
            expected_image = {
                "f1": ("IMAGE", "ALLOWED", "https://img.example.com/111.jpg",
                       "https://img.example.com/111t.jpg", 800, 600,
                       "ILLUSTRATION", "很喜欢这张", 1700000000000),
                "f2": ("IMAGE", "ALLOWED", "https://upload.wikimedia.org/m.jpg",
                       "https://upload.wikimedia.org/mt.jpg", 1024, 768,
                       "LANDSCAPE", "", 1700000001000),
                "f3": ("IMAGE", "ALLOWED", "https://images.metmuseum.org/333.jpg",
                       None, None, None, "OTHER", "", 1700000002000),
                "f4": ("IMAGE", "ALLOWED", "https://www.artic.edu/iiif/2/444.jpg",
                       "https://www.artic.edu/iiif/2/444t.jpg", 843, 600,
                       "ARCHITECTURE", "备注四", 1700000003000),
                "f5": ("IMAGE", "ALLOWED", "https://img.example.com/555.jpg",
                       "https://img.example.com/555t.jpg", 640, 480,
                       "ANIME", "", 1700000004000),
                "f6": ("IMAGE", "ALLOWED", "https://upload.wikimedia.org/l.jpg",
                       None, None, None, "REALISTIC", "", 1700000005000),
                "f11": ("IMAGE", "ALLOWED", "https://future.example.com/1111.jpg",
                        None, None, None, "OTHER", "", 1700000010000),
            }
            for fid, (ctype, status, img, thumb, width, height,
                      cat, note, saved) in expected_image.items():
                row = fav_after.get(fid)
                if row is None:
                    passed &= fail(f"v3->v4 favorite {fid} missing after migration")
                    continue
                if ((row[1], row[2], row[3], row[4], row[5], row[6],
                     row[9], row[8], row[10]) ==
                        (ctype, status, img, thumb, width, height,
                         cat, note, saved)):
                    passed &= ok(f"v3->v4 favorite {fid} stays IMAGE/ALLOWED, "
                                 f"image + note + category kept")
                else:
                    passed &= fail(f"v3->v4 favorite {fid} wrong: got {row}")
            expected_link = {
                "f7": ("https://example.com/ov/777", "这条的备注要保留"),
                "f8": ("https://commons.wikimedia.org/wiki/File:Empty.jpg", ""),
                "f9": ("https://commons.wikimedia.org/wiki/File:Blank.jpg", ""),
                "f10": ("https://example.com/ov/1010", ""),
                "f12": ("https://www.gracg.com/art/1212", "等 5.2 再看"),
            }
            for fid, (url, note) in expected_link.items():
                row = fav_after.get(fid)
                if row is None:
                    passed &= fail(f"v3->v4 favorite {fid} missing after migration")
                    continue
                if ((row[1], row[2]) == ("LINK", "UNKNOWN")
                        and row[3] is None and row[4] is None
                        and row[5] is None and row[6] is None
                        and row[7] == url and row[8] == note):
                    passed &= ok(f"v3->v4 favorite {fid} downgraded to LINK/UNKNOWN, "
                                 f"image fields cleared, link + note kept")
                else:
                    passed &= fail(f"v3->v4 favorite {fid} wrong: got {row}")
            # Per-row classification of cache rows.
            cache_after = {
                r[0]: r for r in conn.execute(
                    "SELECT id, contentType, licenseStatus, imageUrl, "
                    "fetchedAt FROM inspiration_cache").fetchall()
            }
            for cid, (ctype, status, img, fetched) in {
                "c1": ("IMAGE", "ALLOWED", "https://img.example.com/201.jpg",
                       1700000012000),
                "c2": ("LINK", "UNKNOWN", None, 1700000013000),
                "c3": ("IMAGE", "ALLOWED", "https://img.example.com/203.jpg",
                       1700000014000),
            }.items():
                row = cache_after.get(cid)
                if row is None:
                    passed &= fail(f"v3->v4 cache {cid} missing after migration")
                elif ((row[1], row[2], row[3], row[4]) ==
                        (ctype, status, img, fetched)):
                    passed &= ok(f"v3->v4 cache {cid} -> {ctype}/{status}")
                else:
                    passed &= fail(f"v3->v4 cache {cid} wrong: got {row}")

            expected_cols = {
                "inspiration_cache": [
                    ("id", "TEXT", 1, 1), ("category", "TEXT", 1, 2),
                    ("source", "TEXT", 1, 0), ("sourceId", "TEXT", 1, 0),
                    ("title", "TEXT", 1, 0), ("author", "TEXT", 0, 0),
                    ("sourceUrl", "TEXT", 1, 0), ("imageUrl", "TEXT", 0, 0),
                    ("thumbnailUrl", "TEXT", 0, 0), ("width", "INTEGER", 0, 0),
                    ("height", "INTEGER", 0, 0), ("description", "TEXT", 0, 0),
                    ("license", "TEXT", 0, 0), ("licenseUrl", "TEXT", 0, 0),
                    ("dateText", "TEXT", 0, 0), ("place", "TEXT", 0, 0),
                    ("contentType", "TEXT", 1, 0), ("licenseStatus", "TEXT", 1, 0),
                    ("fetchedAt", "INTEGER", 1, 0),
                ],
                "inspiration_favorites": [
                    ("id", "TEXT", 1, 1), ("source", "TEXT", 1, 0),
                    ("sourceId", "TEXT", 1, 0), ("title", "TEXT", 1, 0),
                    ("author", "TEXT", 0, 0), ("sourceUrl", "TEXT", 1, 0),
                    ("imageUrl", "TEXT", 0, 0), ("thumbnailUrl", "TEXT", 0, 0),
                    ("width", "INTEGER", 0, 0), ("height", "INTEGER", 0, 0),
                    ("category", "TEXT", 1, 0), ("description", "TEXT", 0, 0),
                    ("license", "TEXT", 0, 0), ("licenseUrl", "TEXT", 0, 0),
                    ("dateText", "TEXT", 0, 0), ("place", "TEXT", 0, 0),
                    ("contentType", "TEXT", 1, 0), ("licenseStatus", "TEXT", 1, 0),
                    ("savedAt", "INTEGER", 1, 0), ("note", "TEXT", 1, 0),
                ],
            }
            expected_indexes = {
                "inspiration_cache": [
                    ("index_inspiration_cache_category", 0, ("category",)),
                    ("index_inspiration_cache_fetchedAt", 0, ("fetchedAt",)),
                ],
                "inspiration_favorites": [
                    ("index_inspiration_favorites_savedAt", 0, ("savedAt",)),
                ],
            }
            # NOTE: the stored-schema part of check_schema must compare
            # the cache table against its v4-era SQL (migration_3_4),
            # not the current v5 generated SQL — MIGRATION_4_5 drops
            # the cache table, so v5 createAllTables no longer has
            # it. inspiration_favorites is unchanged between v4 and
            # v5, so the generated SQL still covers it.
            v4_cache_create = next(
                s.replace("`inspiration_cache_new`", "`inspiration_cache`")
                for s in migration_3_4
                if normalize(s).startswith(
                    "CREATE TABLE IF NOT EXISTS `inspiration_cache_new`")
            )
            passed &= check_schema(
                conn, [v4_cache_create] + generated_sql,
                ("inspiration_cache", "inspiration_favorites"),
                expected_cols, {}, expected_indexes)
        finally:
            conn.close()

    # --- Check 2+3 (v4 -> v5): create website_favorites, DROP cache ---
    # v4 database: the five non-inspiration tables + inspiration_favorites
    # come from the generated SQL (unchanged between v4 and v5); the
    # cache table comes from MIGRATION_3_4 (its v4 shape, `_new`
    # suffix stripped).
    v4_tables = [s for s in generated_sql
                 if normalize(s).startswith("CREATE TABLE IF NOT EXISTS `artworks`")
                 or normalize(s).startswith("CREATE TABLE IF NOT EXISTS `artwork_images`")
                 or normalize(s).startswith("CREATE TABLE IF NOT EXISTS `tags`")
                 or normalize(s).startswith("CREATE TABLE IF NOT EXISTS `artwork_tag_cross_ref`")
                 or normalize(s).startswith("CREATE TABLE IF NOT EXISTS `journal_entries`")
                 or normalize(s).startswith("CREATE TABLE IF NOT EXISTS `inspiration_favorites`")
                 or normalize(s).startswith("CREATE INDEX IF NOT EXISTS `index_artwork_images")
                 or normalize(s).startswith("CREATE INDEX IF NOT EXISTS `index_tags")
                 or normalize(s).startswith("CREATE INDEX IF NOT EXISTS `index_artwork_tag_cross_ref")
                 or normalize(s).startswith("CREATE INDEX IF NOT EXISTS `index_journal_entries")
                 or normalize(s).startswith("CREATE INDEX IF NOT EXISTS `index_inspiration_favorites")]
    v4_cache = []
    for s in migration_3_4:
        norm = normalize(s)
        if norm.startswith("CREATE TABLE IF NOT EXISTS `inspiration_cache_new`"):
            v4_cache.append(s.replace("`inspiration_cache_new`", "`inspiration_cache`"))
        elif norm.startswith("CREATE INDEX IF NOT EXISTS `index_inspiration_cache"):
            v4_cache.append(s)
    with tempfile.TemporaryDirectory() as tmp:
        db_path = os.path.join(tmp, "v4.db")
        conn = sqlite3.connect(db_path)
        try:
            for stmt in v4_tables + v4_cache:
                conn.execute(stmt)
            # Sample v4 data: 2 artworks, 3 images, 1 tag, 1 link,
            # 1 journal entry, 2 old favorites (one IMAGE/ALLOWED with
            # a note, one LINK/UNKNOWN with a note), 2 cache rows.
            conn.execute(
                "INSERT INTO artworks VALUES "
                "('a1','Sunset',NULL,1700000000000,1700000000000,1700000000000,"
                "45,'COMPLETED','','','','')")
            conn.execute(
                "INSERT INTO artworks VALUES "
                "('a2','Sketch',NULL,1700000000000,1700000000000,1700000000000,"
                "NULL,'DRAFT','','','','')")
            conn.execute(
                "INSERT INTO artwork_images VALUES "
                "('i1','a1','/x/a1/o/1.jpg','/x/a1/t/1.jpg','ORIGINAL',0,"
                "1700000000000,'')")
            conn.execute(
                "INSERT INTO artwork_images VALUES "
                "('i2','a1','/x/a1/o/2.jpg','/x/a1/t/2.jpg','ORIGINAL',1,"
                "1700000000000,'')")
            conn.execute(
                "INSERT INTO artwork_images VALUES "
                "('i3','a2','/x/a2/o/3.jpg','/x/a2/t/3.jpg','ORIGINAL',0,"
                "1700000000000,'')")
            conn.execute(
                "INSERT INTO tags VALUES ('t1','角色',1700000000000)")
            conn.execute(
                "INSERT INTO artwork_tag_cross_ref VALUES ('a1','t1')")
            conn.execute(
                "INSERT INTO journal_entries VALUES "
                "('j1','手记','内容',1700000000000,1700000000000,'a1')")
            fav4_cols = ("id, source, sourceId, title, author, sourceUrl, "
                         "imageUrl, thumbnailUrl, width, height, category, "
                         "description, license, licenseUrl, dateText, place, "
                         "contentType, licenseStatus, savedAt, note")
            fav4_rows = [
                ("f1", "OPENVERSE", "111", "晨光", "画师A",
                 "https://example.com/ov/111", "https://img.example.com/111.jpg",
                 "https://img.example.com/111t.jpg", 800, 600, "ILLUSTRATION",
                 "desc", "CC BY 2.0",
                 "https://creativecommons.org/licenses/by/2.0/",
                 "2024", "", "IMAGE", "ALLOWED", 1700000000000, "很喜欢这张"),
                ("f2", "COMMONS", "222", "山", None,
                 "https://commons.wikimedia.org/wiki/File:Mountain.jpg",
                 None, None, None, None, "LANDSCAPE", None,
                 "All rights reserved", None, None, None,
                 "LINK", "UNKNOWN", 1700000001000, "第二条备注"),
            ]
            for row in fav4_rows:
                conn.execute(
                    f"INSERT INTO inspiration_favorites ({fav4_cols}) "
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", row)
            cache4_cols = ("id, category, source, sourceId, title, author, "
                           "sourceUrl, imageUrl, thumbnailUrl, width, height, "
                           "description, license, licenseUrl, dateText, place, "
                           "contentType, licenseStatus, fetchedAt")
            cache4_rows = [
                ("c1", "ANIME", "OPENVERSE", "201", "缓存一", "画师F",
                 "https://example.com/ov/201", "https://img.example.com/201.jpg",
                 "https://img.example.com/201t.jpg", 800, 600, None,
                 "CC BY 2.0", None, None, None, "IMAGE", "ALLOWED",
                 1700000012000),
                ("c2", "LANDSCAPE", "COMMONS", "202", "缓存二", None,
                 "https://commons.wikimedia.org/wiki/File:C2.jpg",
                 "https://upload.wikimedia.org/c2.jpg", None, None, None,
                 None, None, None, None, None, "LINK", "UNKNOWN",
                 1700000013000),
            ]
            for row in cache4_rows:
                conn.execute(
                    f"INSERT INTO inspiration_cache ({cache4_cols}) "
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", row)
            conn.commit()
            before = {
                "artworks": conn.execute("SELECT COUNT(*) FROM artworks").fetchone()[0],
                "images": conn.execute("SELECT COUNT(*) FROM artwork_images").fetchone()[0],
                "tags": conn.execute("SELECT COUNT(*) FROM tags").fetchone()[0],
                "xrefs": conn.execute(
                    "SELECT COUNT(*) FROM artwork_tag_cross_ref").fetchone()[0],
                "journal": conn.execute(
                    "SELECT COUNT(*) FROM journal_entries").fetchone()[0],
                "favorites": conn.execute(
                    "SELECT COUNT(*) FROM inspiration_favorites").fetchone()[0],
                "cache": conn.execute(
                    "SELECT COUNT(*) FROM inspiration_cache").fetchone()[0],
            }
            fav_before = conn.execute(
                "SELECT * FROM inspiration_favorites ORDER BY id").fetchall()
            for stmt in migration_4_5:
                conn.execute(stmt)
            conn.commit()
            after = {
                "artworks": conn.execute("SELECT COUNT(*) FROM artworks").fetchone()[0],
                "images": conn.execute("SELECT COUNT(*) FROM artwork_images").fetchone()[0],
                "tags": conn.execute("SELECT COUNT(*) FROM tags").fetchone()[0],
                "xrefs": conn.execute(
                    "SELECT COUNT(*) FROM artwork_tag_cross_ref").fetchone()[0],
                "journal": conn.execute(
                    "SELECT COUNT(*) FROM journal_entries").fetchone()[0],
                "favorites": conn.execute(
                    "SELECT COUNT(*) FROM inspiration_favorites").fetchone()[0],
            }
            for key in ("artworks", "images", "tags", "xrefs", "journal",
                        "favorites"):
                if before[key] == after[key]:
                    passed &= ok(f"v4->v5 migration preserves {key}: "
                                 f"{before[key]} -> {after[key]}")
                else:
                    passed &= fail(f"v4->v5 migration CHANGED {key}: "
                                   f"{before[key]} -> {after[key]}")
            # Old favorites survive byte-for-byte (notes included).
            fav_after = conn.execute(
                "SELECT * FROM inspiration_favorites ORDER BY id").fetchall()
            if fav_before == fav_after:
                passed &= ok("v4->v5 old favorites survive byte-for-byte "
                             "(rows + notes unchanged)")
            else:
                passed &= fail(f"v4->v5 old favorites CHANGED: {fav_before} -> {fav_after}")
            # The cache table (and its indexes) are gone.
            cache_table = conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table' "
                "AND name='inspiration_cache'").fetchone()
            if cache_table is None:
                passed &= ok("v4->v5 inspiration_cache table no longer exists")
            else:
                passed &= fail("v4->v5 inspiration_cache table still exists")
            cache_indexes = conn.execute(
                "SELECT name FROM sqlite_master WHERE type='index' "
                "AND name LIKE 'index_inspiration_cache%'").fetchall()
            if not cache_indexes:
                passed &= ok("v4->v5 inspiration_cache indexes are gone with the table")
            else:
                passed &= fail(f"v4->v5 inspiration_cache indexes remain: {cache_indexes}")
            # New table: schema matches the entity, and it works.
            expected_cols = {
                "website_favorites": [
                    ("siteId", "TEXT", 1, 1), ("url", "TEXT", 1, 0),
                    ("name", "TEXT", 0, 0), ("isCustom", "INTEGER", 1, 0),
                    ("categories", "TEXT", 0, 0), ("note", "TEXT", 1, 0),
                    ("savedAt", "INTEGER", 1, 0),
                ],
            }
            expected_indexes = {
                "website_favorites": [
                    ("index_website_favorites_savedAt", 0, ("savedAt",)),
                ],
            }
            passed &= check_schema(
                conn, generated_sql, ("website_favorites",),
                expected_cols, {}, expected_indexes)
            site_cols = ("siteId, url, name, isCustom, categories, note, savedAt")
            conn.execute(
                f"INSERT INTO website_favorites ({site_cols}) VALUES "
                "('chinachahua','https://chinachahua.net/',NULL,0,NULL,'',"
                "1700000020000)")
            conn.execute(
                f"INSERT INTO website_favorites ({site_cols}) VALUES "
                "('custom:11111111-2222-3333-4444-555555555555',"
                "'https://example.com/blog','我的博客',1,'ANIME,REALISTIC',"
                "'常看',1700000021000)")
            conn.commit()
            sites = conn.execute(
                "SELECT siteId, url, name, isCustom, categories, note, savedAt "
                "FROM website_favorites ORDER BY savedAt DESC").fetchall()
            if sites == [
                ("custom:11111111-2222-3333-4444-555555555555",
                 "https://example.com/blog", "我的博客", 1, "ANIME,REALISTIC",
                 "常看", 1700000021000),
                ("chinachahua", "https://chinachahua.net/", None, 0, None,
                 "", 1700000020000),
            ]:
                passed &= ok("v4->v5 website_favorites accepts official + "
                             "custom rows, read back in savedAt DESC order")
            else:
                passed &= fail(f"v4->v5 website_favorites rows wrong: {sites}")
            # REPLACE on the same siteId updates the row instead of
            # duplicating it (the DAO inserts with REPLACE).
            conn.execute(
                f"INSERT OR REPLACE INTO website_favorites ({site_cols}) VALUES "
                "('chinachahua','https://chinachahua.net/',NULL,0,NULL,"
                "'改过备注',1700000022000)")
            conn.commit()
            replaced = conn.execute(
                "SELECT note, savedAt FROM website_favorites "
                "WHERE siteId='chinachahua'").fetchone()
            count = conn.execute(
                "SELECT COUNT(*) FROM website_favorites").fetchone()[0]
            if replaced == ("改过备注", 1700000022000) and count == 2:
                passed &= ok("v4->v5 website_favorites REPLACE on same siteId "
                             "updates the row (count stays 2)")
            else:
                passed &= fail(f"v4->v5 website_favorites REPLACE wrong: "
                               f"{replaced}, count={count}")
        finally:
            conn.close()

    # --- Check 2+3 (v5 -> v6): create the three v6 tables, seed categories ---
    # v5 database: all seven v5 tables come from the generated SQL
    # (unchanged between v5 and v6).
    v5_tables = [s for s in generated_sql
                 if normalize(s).startswith("CREATE TABLE IF NOT EXISTS `artworks`")
                 or normalize(s).startswith("CREATE TABLE IF NOT EXISTS `artwork_images`")
                 or normalize(s).startswith("CREATE TABLE IF NOT EXISTS `tags`")
                 or normalize(s).startswith("CREATE TABLE IF NOT EXISTS `artwork_tag_cross_ref`")
                 or normalize(s).startswith("CREATE TABLE IF NOT EXISTS `journal_entries`")
                 or normalize(s).startswith("CREATE TABLE IF NOT EXISTS `inspiration_favorites`")
                 or normalize(s).startswith("CREATE TABLE IF NOT EXISTS `website_favorites`")
                 or normalize(s).startswith("CREATE INDEX IF NOT EXISTS `index_artwork_images")
                 or normalize(s).startswith("CREATE INDEX IF NOT EXISTS `index_tags")
                 or normalize(s).startswith("CREATE INDEX IF NOT EXISTS `index_artwork_tag_cross_ref")
                 or normalize(s).startswith("CREATE INDEX IF NOT EXISTS `index_journal_entries")
                 or normalize(s).startswith("CREATE INDEX IF NOT EXISTS `index_inspiration_favorites")
                 or normalize(s).startswith("CREATE INDEX IF NOT EXISTS `index_website_favorites")]
    with tempfile.TemporaryDirectory() as tmp:
        db_path = os.path.join(tmp, "v5.db")
        conn = sqlite3.connect(db_path)
        try:
            for stmt in v5_tables:
                conn.execute(stmt)
            # Sample v5 data: 2 artworks, 3 images, 1 tag, 1 link,
            # 1 journal entry, 2 old favorites (one IMAGE/ALLOWED with
            # a note, one LINK/UNKNOWN with a note), 2 website
            # favorites (one official, one custom).
            conn.execute(
                "INSERT INTO artworks VALUES "
                "('a1','Sunset',NULL,1700000000000,1700000000000,1700000000000,"
                "45,'COMPLETED','','','','')")
            conn.execute(
                "INSERT INTO artworks VALUES "
                "('a2','Sketch',NULL,1700000000000,1700000000000,1700000000000,"
                "NULL,'DRAFT','','','','')")
            conn.execute(
                "INSERT INTO artwork_images VALUES "
                "('i1','a1','/x/a1/o/1.jpg','/x/a1/t/1.jpg','ORIGINAL',0,"
                "1700000000000,'')")
            conn.execute(
                "INSERT INTO artwork_images VALUES "
                "('i2','a1','/x/a1/o/2.jpg','/x/a1/t/2.jpg','ORIGINAL',1,"
                "1700000000000,'')")
            conn.execute(
                "INSERT INTO artwork_images VALUES "
                "('i3','a2','/x/a2/o/3.jpg','/x/a2/t/3.jpg','ORIGINAL',0,"
                "1700000000000,'')")
            conn.execute(
                "INSERT INTO tags VALUES ('t1','角色',1700000000000)")
            conn.execute(
                "INSERT INTO artwork_tag_cross_ref VALUES ('a1','t1')")
            conn.execute(
                "INSERT INTO journal_entries VALUES "
                "('j1','手记','内容',1700000000000,1700000000000,'a1')")
            fav5_cols = ("id, source, sourceId, title, author, sourceUrl, "
                         "imageUrl, thumbnailUrl, width, height, category, "
                         "description, license, licenseUrl, dateText, place, "
                         "contentType, licenseStatus, savedAt, note")
            fav5_rows = [
                ("f1", "OPENVERSE", "111", "晨光", "画师A",
                 "https://example.com/ov/111", "https://img.example.com/111.jpg",
                 "https://img.example.com/111t.jpg", 800, 600, "ILLUSTRATION",
                 "desc", "CC BY 2.0",
                 "https://creativecommons.org/licenses/by/2.0/",
                 "2024", "", "IMAGE", "ALLOWED", 1700000000000, "很喜欢这张"),
                ("f2", "COMMONS", "222", "山", None,
                 "https://commons.wikimedia.org/wiki/File:Mountain.jpg",
                 None, None, None, None, "LANDSCAPE", None,
                 "All rights reserved", None, None, None,
                 "LINK", "UNKNOWN", 1700000001000, "第二条备注"),
            ]
            for row in fav5_rows:
                conn.execute(
                    f"INSERT INTO inspiration_favorites ({fav5_cols}) "
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", row)
            site5_cols = ("siteId, url, name, isCustom, categories, note, savedAt")
            conn.execute(
                f"INSERT INTO website_favorites ({site5_cols}) VALUES "
                "('chinachahua','https://chinachahua.net/',NULL,0,NULL,'',"
                "1700000020000)")
            conn.execute(
                f"INSERT INTO website_favorites ({site5_cols}) VALUES "
                "('custom:11111111-2222-3333-4444-555555555555',"
                "'https://example.com/blog','我的博客',1,'ANIME,REALISTIC',"
                "'常看',1700000021000)")
            conn.commit()
            before = {
                "artworks": conn.execute("SELECT COUNT(*) FROM artworks").fetchone()[0],
                "images": conn.execute("SELECT COUNT(*) FROM artwork_images").fetchone()[0],
                "tags": conn.execute("SELECT COUNT(*) FROM tags").fetchone()[0],
                "xrefs": conn.execute(
                    "SELECT COUNT(*) FROM artwork_tag_cross_ref").fetchone()[0],
                "journal": conn.execute(
                    "SELECT COUNT(*) FROM journal_entries").fetchone()[0],
                "favorites": conn.execute(
                    "SELECT COUNT(*) FROM inspiration_favorites").fetchone()[0],
                "sites": conn.execute(
                    "SELECT COUNT(*) FROM website_favorites").fetchone()[0],
            }
            fav_before = conn.execute(
                "SELECT * FROM inspiration_favorites ORDER BY id").fetchall()
            sites_before = conn.execute(
                "SELECT * FROM website_favorites ORDER BY siteId").fetchall()
            for stmt in migration_5_6:
                conn.execute(stmt)
            conn.commit()
            after = {
                "artworks": conn.execute("SELECT COUNT(*) FROM artworks").fetchone()[0],
                "images": conn.execute("SELECT COUNT(*) FROM artwork_images").fetchone()[0],
                "tags": conn.execute("SELECT COUNT(*) FROM tags").fetchone()[0],
                "xrefs": conn.execute(
                    "SELECT COUNT(*) FROM artwork_tag_cross_ref").fetchone()[0],
                "journal": conn.execute(
                    "SELECT COUNT(*) FROM journal_entries").fetchone()[0],
                "favorites": conn.execute(
                    "SELECT COUNT(*) FROM inspiration_favorites").fetchone()[0],
                "sites": conn.execute(
                    "SELECT COUNT(*) FROM website_favorites").fetchone()[0],
                "overrides": conn.execute(
                    "SELECT COUNT(*) FROM website_overrides").fetchone()[0],
                "categories": conn.execute(
                    "SELECT COUNT(*) FROM directory_categories").fetchone()[0],
                "tracks": conn.execute(
                    "SELECT COUNT(*) FROM music_tracks").fetchone()[0],
            }
            for key in ("artworks", "images", "tags", "xrefs", "journal",
                        "favorites", "sites"):
                if before[key] == after[key]:
                    passed &= ok(f"v5->v6 migration preserves {key}: "
                                 f"{before[key]} -> {after[key]}")
                else:
                    passed &= fail(f"v5->v6 migration CHANGED {key}: "
                                   f"{before[key]} -> {after[key]}")
            # Old favorites and website favorites survive byte-for-byte.
            fav_after = conn.execute(
                "SELECT * FROM inspiration_favorites ORDER BY id").fetchall()
            if fav_before == fav_after:
                passed &= ok("v5->v6 old favorites survive byte-for-byte "
                             "(rows + notes unchanged)")
            else:
                passed &= fail(f"v5->v6 old favorites CHANGED: {fav_before} -> {fav_after}")
            sites_after = conn.execute(
                "SELECT * FROM website_favorites ORDER BY siteId").fetchall()
            if sites_before == sites_after:
                passed &= ok("v5->v6 website favorites survive byte-for-byte")
            else:
                passed &= fail(f"v5->v6 website favorites CHANGED: {sites_before} -> {sites_after}")
            # New tables: overrides and tracks start empty, categories
            # hold exactly the 5 builtin seed rows, in sortOrder.
            if (after["overrides"], after["tracks"]) == (0, 0):
                passed &= ok("v5->v6 new tables start empty (overrides/tracks = 0)")
            else:
                passed &= fail("v5->v6 new tables not empty after migration: "
                               f"overrides={after['overrides']}, tracks={after['tracks']}")
            seeds = conn.execute(
                "SELECT id, name, sortOrder, isBuiltin, isHidden, createdAt "
                "FROM directory_categories ORDER BY sortOrder").fetchall()
            if seeds == [
                ("ANIME", "二次元插画", 0, 1, 0, 1700000000000),
                ("REALISTIC", "写实插画", 1, 1, 0, 1700000000000),
                ("PHOTOGRAPHY", "情绪摄影", 2, 1, 0, 1700000000000),
                ("ARCHITECTURE", "建筑美学", 3, 1, 0, 1700000000000),
                ("LANDSCAPE", "风景与氛围", 4, 1, 0, 1700000000000),
            ]:
                passed &= ok("v5->v6 directory_categories holds exactly the 5 "
                             "builtin seed rows in order")
            else:
                passed &= fail(f"v5->v6 seed categories wrong: {seeds}")
            # Re-running the migration must not duplicate the seeds
            # (INSERT OR IGNORE).
            for stmt in migration_5_6:
                conn.execute(stmt)
            conn.commit()
            if conn.execute("SELECT COUNT(*) FROM directory_categories").fetchone()[0] == 5:
                passed &= ok("v5->v6 migration is idempotent for seed rows (still 5)")
            else:
                passed &= fail("v5->v6 migration duplicated seed rows on re-run")
            # New tables: schema matches the entities, and they work.
            expected_cols = {
                "website_overrides": [
                    ("siteId", "TEXT", 1, 1), ("isDeleted", "INTEGER", 1, 0),
                    ("nameOverride", "TEXT", 0, 0),
                    ("descriptionOverride", "TEXT", 0, 0),
                    ("urlOverride", "TEXT", 0, 0),
                    ("categoriesOverride", "TEXT", 0, 0),
                    ("updatedAt", "INTEGER", 1, 0),
                ],
                "directory_categories": [
                    ("id", "TEXT", 1, 1), ("name", "TEXT", 1, 0),
                    ("sortOrder", "INTEGER", 1, 0), ("isBuiltin", "INTEGER", 1, 0),
                    ("isHidden", "INTEGER", 1, 0), ("createdAt", "INTEGER", 1, 0),
                ],
                "music_tracks": [
                    ("id", "TEXT", 1, 1), ("title", "TEXT", 1, 0),
                    ("artist", "TEXT", 0, 0), ("album", "TEXT", 0, 0),
                    ("durationMs", "INTEGER", 0, 0), ("fileName", "TEXT", 1, 0),
                    ("filePath", "TEXT", 1, 0), ("fileSizeBytes", "INTEGER", 1, 0),
                    ("mimeType", "TEXT", 0, 0), ("lyricsText", "TEXT", 0, 0),
                    ("lyricsSource", "TEXT", 1, 0), ("lyricsFilePath", "TEXT", 0, 0),
                    ("importedAt", "INTEGER", 1, 0),
                ],
            }
            expected_indexes = {
                "website_overrides": [
                    ("index_website_overrides_updatedAt", 0, ("updatedAt",)),
                ],
                "directory_categories": [
                    ("index_directory_categories_sortOrder", 0, ("sortOrder",)),
                ],
                "music_tracks": [
                    ("index_music_tracks_importedAt", 0, ("importedAt",)),
                    ("index_music_tracks_title", 0, ("title",)),
                ],
            }
            # NOTE: `music_tracks` must be compared against its v6-era
            # SQL (MIGRATION_5_6), not the current v7 generated SQL —
            # the v6 table genuinely lacks the v7 `coverPath` column,
            # and that is expected, not a mismatch. The other two
            # tables are unchanged between v6 and v7, so the generated
            # SQL still covers them.
            passed &= check_schema(
                conn, generated_sql,
                ("website_overrides", "directory_categories"),
                {k: v for k, v in expected_cols.items()
                 if k != "music_tracks"},
                {}, {k: v for k, v in expected_indexes.items()
                     if k != "music_tracks"})
            passed &= check_schema(
                conn, migration_5_6,
                ("music_tracks",),
                {"music_tracks": expected_cols["music_tracks"]},
                {}, {"music_tracks": expected_indexes["music_tracks"]})
            # Insert / read-back on each new table.
            conn.execute(
                "INSERT INTO website_overrides "
                "(siteId, isDeleted, nameOverride, descriptionOverride, "
                "urlOverride, categoriesOverride, updatedAt) VALUES "
                "('uisdc',1,NULL,'简介覆盖',NULL,NULL,1700000030000)")
            conn.execute(
                "INSERT INTO directory_categories "
                "(id, name, sortOrder, isBuiltin, isHidden, createdAt) VALUES "
                "('cat:11111111-2222-3333-4444-555555555555','我的分类',5,0,0,"
                "1700000031000)")
            conn.execute(
                "INSERT INTO music_tracks "
                "(id, title, artist, album, durationMs, fileName, filePath, "
                "fileSizeBytes, mimeType, lyricsText, lyricsSource, "
                "lyricsFilePath, importedAt) VALUES "
                "('track:22222222-3333-4444-5555-666666666666','晨光','画师A',"
                "NULL,180000,'track-22222222.mp3',"
                "'/data/data/com.artlog.app/files/Artlog/music/track:22222222-3333-4444-5555-666666666666.mp3',"
                "123456,'audio/mpeg',NULL,'NONE',NULL,1700000032000)")
            conn.commit()
            override_row = conn.execute(
                "SELECT isDeleted, descriptionOverride FROM website_overrides "
                "WHERE siteId='uisdc'"
            ).fetchone()
            custom_cat = conn.execute(
                "SELECT name, sortOrder FROM directory_categories "
                "WHERE id='cat:11111111-2222-3333-4444-555555555555'"
            ).fetchone()
            track_row = conn.execute(
                "SELECT title, artist, lyricsSource FROM music_tracks "
                "WHERE id='track:22222222-3333-4444-5555-666666666666'"
            ).fetchone()
            if override_row == (1, "简介覆盖"):
                passed &= ok("v5->v6 website_overrides accepts a hidden-site row "
                             "with a descriptionOverride")
            else:
                passed &= fail(f"v5->v6 website_overrides row wrong: {override_row}")
            if custom_cat == ("我的分类", 5):
                passed &= ok("v5->v6 directory_categories accepts a custom row")
            else:
                passed &= fail(f"v5->v6 directory_categories row wrong: {custom_cat}")
            if track_row == ("晨光", "画师A", "NONE"):
                passed &= ok("v5->v6 music_tracks accepts a track row")
            else:
                passed &= fail(f"v5->v6 music_tracks row wrong: {track_row}")
            # REPLACE on the same siteId updates the row (the DAO
            # inserts with REPLACE).
            conn.execute(
                "INSERT OR REPLACE INTO website_overrides "
                "(siteId, isDeleted, nameOverride, descriptionOverride, "
                "urlOverride, categoriesOverride, updatedAt) VALUES "
                "('uisdc',0,'优设改名',NULL,NULL,NULL,1700000033000)")
            conn.commit()
            replaced = conn.execute(
                "SELECT isDeleted, nameOverride FROM website_overrides "
                "WHERE siteId='uisdc'").fetchone()
            count = conn.execute(
                "SELECT COUNT(*) FROM website_overrides").fetchone()[0]
            if replaced == (0, "优设改名") and count == 1:
                passed &= ok("v5->v6 website_overrides REPLACE on same siteId "
                             "updates the row (count stays 1)")
            else:
                passed &= fail(f"v5->v6 website_overrides REPLACE wrong: "
                               f"{replaced}, count={count}")
        finally:
            conn.close()

    # --- Check 2+3 (v6 -> v7): ADD COLUMN coverPath to music_tracks ---
    # v6 database: the v6 `music_tracks` table comes from MIGRATION_5_6
    # (its v6 shape); every other v6 table comes from the generated SQL
    # (unchanged between v6 and v7). The migration touches no other
    # table, so only music_tracks rows and one artwork row (a control
    # for "other tables untouched") are inserted.
    v6_music = [s for s in migration_5_6
                if normalize(s).startswith("CREATE TABLE IF NOT EXISTS `music_tracks`")
                or normalize(s).startswith("CREATE INDEX IF NOT EXISTS `index_music_tracks")]
    v6_other = [s for s in generated_sql
                if "music_tracks" not in normalize(s)
                and (normalize(s).startswith("CREATE TABLE IF NOT EXISTS")
                     or normalize(s).startswith("CREATE INDEX IF NOT EXISTS"))]
    # NOTE: parse_generated_sql returns every execSQL in the Impl file,
    # including dropAllTables / clearAllTables (DROP TABLE, DELETE FROM,
    # VACUUM, PRAGMA) — those must NOT run here, or the tables created
    # above are dropped again before the INSERTs below (that was the
    # "no such table: artworks" failure). Same CREATE-only filter the
    # v1 data test uses (v1_tables above).
    with tempfile.TemporaryDirectory() as tmp:
        db_path = os.path.join(tmp, "v6.db")
        conn = sqlite3.connect(db_path)
        try:
            for stmt in v6_other + v6_music:
                conn.execute(stmt)
            conn.execute(
                "INSERT INTO artworks VALUES "
                "('a1','Sunset',NULL,1700000000000,1700000000000,1700000000000,"
                "45,'COMPLETED','','','','')")
            track_cols = ("id, title, artist, album, durationMs, fileName, "
                          "filePath, fileSizeBytes, mimeType, lyricsText, "
                          "lyricsSource, lyricsFilePath, importedAt")
            conn.execute(
                f"INSERT INTO music_tracks ({track_cols}) VALUES "
                "('track:11111111-2222-3333-4444-555555555555','晨光','画师A',"
                "NULL,180000,'track-11111111.mp3',"
                "'/data/data/com.artlog.app/files/Artlog/music/track-11111111.mp3',"
                "123456,'audio/mpeg',NULL,'NONE',NULL,1700000032000)")
            conn.execute(
                f"INSERT INTO music_tracks ({track_cols}) VALUES "
                "('track:22222222-3333-4444-5555-666666666666','夜曲',NULL,"
                "'专辑B',240000,'track-22222222.flac',"
                "'/data/data/com.artlog.app/files/Artlog/music/track-22222222.flac',"
                "654321,'audio/flac','[00:01.00]夜','EMBEDDED',NULL,"
                "1700000033000)")
            conn.commit()
            before = {
                "artworks": conn.execute("SELECT COUNT(*) FROM artworks").fetchone()[0],
                "tracks": conn.execute("SELECT COUNT(*) FROM music_tracks").fetchone()[0],
                "t1": conn.execute(
                    "SELECT title, artist, lyricsSource FROM music_tracks "
                    "WHERE id='track:11111111-2222-3333-4444-555555555555'"
                ).fetchone(),
                "t2": conn.execute(
                    "SELECT title, album, lyricsText FROM music_tracks "
                    "WHERE id='track:22222222-3333-4444-5555-666666666666'"
                ).fetchone(),
            }
            for stmt in migration_6_7:
                conn.execute(stmt)
            conn.commit()
            after = {
                "artworks": conn.execute("SELECT COUNT(*) FROM artworks").fetchone()[0],
                "tracks": conn.execute("SELECT COUNT(*) FROM music_tracks").fetchone()[0],
                "t1": conn.execute(
                    "SELECT title, artist, lyricsSource FROM music_tracks "
                    "WHERE id='track:11111111-2222-3333-4444-555555555555'"
                ).fetchone(),
                "t2": conn.execute(
                    "SELECT title, album, lyricsText FROM music_tracks "
                    "WHERE id='track:22222222-3333-4444-5555-666666666666'"
                ).fetchone(),
                "null_covers": conn.execute(
                    "SELECT COUNT(*) FROM music_tracks WHERE coverPath IS NULL"
                ).fetchone()[0],
            }
            for key in ("artworks", "tracks", "t1", "t2"):
                if before[key] == after[key]:
                    passed &= ok(f"v6->v7 migration preserves {key}: "
                                 f"{before[key]} -> {after[key]}")
                else:
                    passed &= fail(f"v6->v7 migration CHANGED {key}: "
                                   f"{before[key]} -> {after[key]}")
            if after["null_covers"] == 2:
                passed &= ok("v6->v7 old track rows get coverPath NULL (2/2)")
            else:
                passed &= fail(f"v6->v7 old track rows coverPath not NULL: "
                               f"{after['null_covers']}/2")
            # Post-migration schema: v7 columns (coverPath last, nullable
            # TEXT), indexes unchanged, stored schema == generated v7 SQL.
            expected_cols_7 = {
                "music_tracks": [
                    ("id", "TEXT", 1, 1), ("title", "TEXT", 1, 0),
                    ("artist", "TEXT", 0, 0), ("album", "TEXT", 0, 0),
                    ("durationMs", "INTEGER", 0, 0), ("fileName", "TEXT", 1, 0),
                    ("filePath", "TEXT", 1, 0), ("fileSizeBytes", "INTEGER", 1, 0),
                    ("mimeType", "TEXT", 0, 0), ("lyricsText", "TEXT", 0, 0),
                    ("lyricsSource", "TEXT", 1, 0), ("lyricsFilePath", "TEXT", 0, 0),
                    ("importedAt", "INTEGER", 1, 0), ("coverPath", "TEXT", 0, 0),
                ],
            }
            expected_indexes_7 = {
                "music_tracks": [
                    ("index_music_tracks_importedAt", 0, ("importedAt",)),
                    ("index_music_tracks_title", 0, ("title",)),
                ],
            }
            passed &= check_schema(
                conn, generated_sql, ("music_tracks",),
                expected_cols_7, {}, expected_indexes_7)
            # A new track row with a coverPath inserts and reads back.
            conn.execute(
                "INSERT INTO music_tracks "
                "(id, title, artist, album, durationMs, fileName, filePath, "
                "fileSizeBytes, mimeType, lyricsText, lyricsSource, "
                "lyricsFilePath, importedAt, coverPath) VALUES "
                "('track:33333333-4444-5555-6666-777777777777','新歌','歌手C',"
                "NULL,200000,'track-33333333.mp3',"
                "'/data/data/com.artlog.app/files/Artlog/music/track-33333333.mp3',"
                "111111,'audio/mpeg',NULL,'NONE',NULL,1700000034000,"
                "'/data/data/com.artlog.app/files/Artlog/music/covers/"
                "track-33333333-4444-5555-6666-777777777777.jpg')")
            conn.commit()
            new_row = conn.execute(
                "SELECT title, coverPath FROM music_tracks "
                "WHERE id='track:33333333-4444-5555-6666-777777777777'"
            ).fetchone()
            if new_row == ("新歌",
                           "/data/data/com.artlog.app/files/Artlog/music/covers/"
                           "track-33333333-4444-5555-6666-777777777777.jpg"):
                passed &= ok("v6->v7 music_tracks accepts a new track row "
                             "with a coverPath")
            else:
                passed &= fail(f"v6->v7 new track row with coverPath wrong: {new_row}")
        finally:
            conn.close()

    print("MIGRATION CHECK: " + ("ALL PASS" if passed else "FAILED"))
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
