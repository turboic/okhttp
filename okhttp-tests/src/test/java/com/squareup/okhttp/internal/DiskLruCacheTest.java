/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.okhttp.internal;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.Executor;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;
import okio.Source;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.Timeout;

import static com.squareup.okhttp.internal.DiskLruCache.JOURNAL_FILE;
import static com.squareup.okhttp.internal.DiskLruCache.JOURNAL_FILE_BACKUP;
import static com.squareup.okhttp.internal.DiskLruCache.MAGIC;
import static com.squareup.okhttp.internal.DiskLruCache.VERSION_1;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class DiskLruCacheTest {
  @Rule public final TemporaryFolder tempDir = new TemporaryFolder();
  @Rule public final Timeout timeout = new Timeout(30 * 1000);

  private final int appVersion = 100;
  private File cacheDir;
  private File journalFile;
  private File journalBkpFile;
  private final TestExecutor executor = new TestExecutor();

  private DiskLruCache cache;
  private final Deque<DiskLruCache> toClose = new ArrayDeque<>();

  private void createNewCache() throws IOException {
    createNewCacheWithSize(Integer.MAX_VALUE);
  }

  private void createNewCacheWithSize(int maxSize) throws IOException {
    cache = new DiskLruCache(cacheDir, appVersion, 2, maxSize, executor);
    synchronized (cache) {
      cache.initialize();
    }
    toClose.add(cache);
  }

  @Before public void setUp() throws Exception {
    cacheDir = tempDir.getRoot();
    journalFile = new File(cacheDir, JOURNAL_FILE);
    journalBkpFile = new File(cacheDir, JOURNAL_FILE_BACKUP);
    createNewCache();
  }

  @After public void tearDown() throws Exception {
    while (!toClose.isEmpty()) {
      toClose.pop().close();
    }
  }

  @Test public void emptyCache() throws Exception {
    cache.close();
    assertJournalEquals();
  }

  @Test public void validateKey() throws Exception {
    String key = null;
    try {
      key = "has_space ";
      cache.edit(key);
      fail("Exepcting an IllegalArgumentException as the key was invalid.");
    } catch (IllegalArgumentException iae) {
      assertEquals("keys must match regex [a-z0-9_-]{1,120}: \"" + key + "\"", iae.getMessage());
    }
    try {
      key = "has_CR\r";
      cache.edit(key);
      fail("Exepcting an IllegalArgumentException as the key was invalid.");
    } catch (IllegalArgumentException iae) {
      assertEquals("keys must match regex [a-z0-9_-]{1,120}: \"" + key + "\"", iae.getMessage());
    }
    try {
      key = "has_LF\n";
      cache.edit(key);
      fail("Exepcting an IllegalArgumentException as the key was invalid.");
    } catch (IllegalArgumentException iae) {
      assertEquals("keys must match regex [a-z0-9_-]{1,120}: \"" + key + "\"", iae.getMessage());
    }
    try {
      key = "has_invalid/";
      cache.edit(key);
      fail("Exepcting an IllegalArgumentException as the key was invalid.");
    } catch (IllegalArgumentException iae) {
      assertEquals("keys must match regex [a-z0-9_-]{1,120}: \"" + key + "\"", iae.getMessage());
    }
    try {
      key = "has_invalid\u2603";
      cache.edit(key);
      fail("Exepcting an IllegalArgumentException as the key was invalid.");
    } catch (IllegalArgumentException iae) {
      assertEquals("keys must match regex [a-z0-9_-]{1,120}: \"" + key + "\"", iae.getMessage());
    }
    try {
      key = "this_is_way_too_long_this_is_way_too_long_this_is_way_too_long_"
          + "this_is_way_too_long_this_is_way_too_long_this_is_way_too_long";
      cache.edit(key);
      fail("Exepcting an IllegalArgumentException as the key was too long.");
    } catch (IllegalArgumentException iae) {
      assertEquals("keys must match regex [a-z0-9_-]{1,120}: \"" + key + "\"", iae.getMessage());
    }

    // Test valid cases.

    // Exactly 120.
    key = "0123456789012345678901234567890123456789012345678901234567890123456789"
        + "01234567890123456789012345678901234567890123456789";
    cache.edit(key).abort();
    // Contains all valid characters.
    key = "abcdefghijklmnopqrstuvwxyz_0123456789";
    cache.edit(key).abort();
    // Contains dash.
    key = "-20384573948576";
    cache.edit(key).abort();
  }

  @Test public void writeAndReadEntry() throws Exception {
    DiskLruCache.Editor creator = cache.edit("k1");
    setString(creator, 0, "ABC");
    setString(creator, 1, "DE");
    assertNull(creator.newSource(0));
    assertNull(creator.newSource(1));
    creator.commit();

    DiskLruCache.Snapshot snapshot = cache.get("k1");
    assertSnapshotValue(snapshot, "ABC", 0);
    assertSnapshotValue(snapshot, "DE", 1);
  }

  @Test public void readAndWriteEntryAcrossCacheOpenAndClose() throws Exception {
    DiskLruCache.Editor creator = cache.edit("k1");
    setString(creator, 0, "A");
    setString(creator, 1, "B");
    creator.commit();
    cache.close();

    createNewCache();
    DiskLruCache.Snapshot snapshot = cache.get("k1");
    assertSnapshotValue(snapshot, "A", 0);
    assertSnapshotValue(snapshot, "B", 1);
    snapshot.close();
  }

  @Test public void readAndWriteEntryWithoutProperClose() throws Exception {
    DiskLruCache.Editor creator = cache.edit("k1");
    setString(creator, 0, "A");
    setString(creator, 1, "B");
    creator.commit();

    // Simulate a dirty close of 'cache' by opening the cache directory again.
    createNewCache();
    DiskLruCache.Snapshot snapshot = cache.get("k1");
    assertSnapshotValue(snapshot, "A", 0);
    assertSnapshotValue(snapshot, "B", 1);
    snapshot.close();
  }

  @Test public void journalWithEditAndPublish() throws Exception {
    DiskLruCache.Editor creator = cache.edit("k1");
    assertJournalEquals("DIRTY k1"); // DIRTY must always be flushed.
    setString(creator, 0, "AB");
    setString(creator, 1, "C");
    creator.commit();
    cache.close();
    assertJournalEquals("DIRTY k1", "CLEAN k1 2 1");
  }

  @Test public void revertedNewFileIsRemoveInJournal() throws Exception {
    DiskLruCache.Editor creator = cache.edit("k1");
    assertJournalEquals("DIRTY k1"); // DIRTY must always be flushed.
    setString(creator, 0, "AB");
    setString(creator, 1, "C");
    creator.abort();
    cache.close();
    assertJournalEquals("DIRTY k1", "REMOVE k1");
  }

  @Test public void unterminatedEditIsRevertedOnClose() throws Exception {
    cache.edit("k1");
    cache.close();
    assertJournalEquals("DIRTY k1", "REMOVE k1");
  }

  @Test public void journalDoesNotIncludeReadOfYetUnpublishedValue() throws Exception {
    DiskLruCache.Editor creator = cache.edit("k1");
    assertNull(cache.get("k1"));
    setString(creator, 0, "A");
    setString(creator, 1, "BC");
    creator.commit();
    cache.close();
    assertJournalEquals("DIRTY k1", "CLEAN k1 1 2");
  }

  @Test public void journalWithEditAndPublishAndRead() throws Exception {
    DiskLruCache.Editor k1Creator = cache.edit("k1");
    setString(k1Creator, 0, "AB");
    setString(k1Creator, 1, "C");
    k1Creator.commit();
    DiskLruCache.Editor k2Creator = cache.edit("k2");
    setString(k2Creator, 0, "DEF");
    setString(k2Creator, 1, "G");
    k2Creator.commit();
    DiskLruCache.Snapshot k1Snapshot = cache.get("k1");
    k1Snapshot.close();
    cache.close();
    assertJournalEquals("DIRTY k1", "CLEAN k1 2 1", "DIRTY k2", "CLEAN k2 3 1", "READ k1");
  }

  @Test public void cannotOperateOnEditAfterPublish() throws Exception {
    DiskLruCache.Editor editor = cache.edit("k1");
    setString(editor, 0, "A");
    setString(editor, 1, "B");
    editor.commit();
    assertInoperable(editor);
  }

  @Test public void cannotOperateOnEditAfterRevert() throws Exception {
    DiskLruCache.Editor editor = cache.edit("k1");
    setString(editor, 0, "A");
    setString(editor, 1, "B");
    editor.abort();
    assertInoperable(editor);
  }

  @Test public void explicitRemoveAppliedToDiskImmediately() throws Exception {
    DiskLruCache.Editor editor = cache.edit("k1");
    setString(editor, 0, "ABC");
    setString(editor, 1, "B");
    editor.commit();
    File k1 = getCleanFile("k1", 0);
    assertEquals("ABC", readFile(k1));
    cache.remove("k1");
    assertFalse(k1.exists());
  }

  @Test public void removePreventsActiveEditFromStoringAValue() throws Exception {
    set("a", "a", "a");
    DiskLruCache.Editor a = cache.edit("a");
    setString(a, 0, "a1");
    assertTrue(cache.remove("a"));
    setString(a, 1, "a2");
    a.commit();
    assertAbsent("a");
  }

  /**
   * Each read sees a snapshot of the file at the time read was called.
   * This means that two reads of the same key can see different data.
   */
  @Test public void readAndWriteOverlapsMaintainConsistency() throws Exception {
    DiskLruCache.Editor v1Creator = cache.edit("k1");
    setString(v1Creator, 0, "AAaa");
    setString(v1Creator, 1, "BBbb");
    v1Creator.commit();

    DiskLruCache.Snapshot snapshot1 = cache.get("k1");
    BufferedSource inV1 = Okio.buffer(snapshot1.getSource(0));
    assertEquals('A', inV1.readByte());
    assertEquals('A', inV1.readByte());

    DiskLruCache.Editor v1Updater = cache.edit("k1");
    setString(v1Updater, 0, "CCcc");
    setString(v1Updater, 1, "DDdd");
    v1Updater.commit();

    DiskLruCache.Snapshot snapshot2 = cache.get("k1");
    assertSnapshotValue(snapshot2, "CCcc", 0);
    assertSnapshotValue(snapshot2, "DDdd", 1);
    snapshot2.close();

    assertEquals('a', inV1.readByte());
    assertEquals('a', inV1.readByte());
    assertSnapshotValue(snapshot1, "BBbb", 1);
    snapshot1.close();
  }

  @Test public void openWithDirtyKeyDeletesAllFilesForThatKey() throws Exception {
    cache.close();
    File cleanFile0 = getCleanFile("k1", 0);
    File cleanFile1 = getCleanFile("k1", 1);
    File dirtyFile0 = getDirtyFile("k1", 0);
    File dirtyFile1 = getDirtyFile("k1", 1);
    writeFile(cleanFile0, "A");
    writeFile(cleanFile1, "B");
    writeFile(dirtyFile0, "C");
    writeFile(dirtyFile1, "D");
    createJournal("CLEAN k1 1 1", "DIRTY   k1");
    createNewCache();
    assertFalse(cleanFile0.exists());
    assertFalse(cleanFile1.exists());
    assertFalse(dirtyFile0.exists());
    assertFalse(dirtyFile1.exists());
    assertNull(cache.get("k1"));
  }

  @Test public void openWithInvalidVersionClearsDirectory() throws Exception {
    cache.close();
    generateSomeGarbageFiles();
    createJournalWithHeader(MAGIC, "0", "100", "2", "");
    createNewCache();
    assertGarbageFilesAllDeleted();
  }

  @Test public void openWithInvalidAppVersionClearsDirectory() throws Exception {
    cache.close();
    generateSomeGarbageFiles();
    createJournalWithHeader(MAGIC, "1", "101", "2", "");
    createNewCache();
    assertGarbageFilesAllDeleted();
  }

  @Test public void openWithInvalidValueCountClearsDirectory() throws Exception {
    cache.close();
    generateSomeGarbageFiles();
    createJournalWithHeader(MAGIC, "1", "100", "1", "");
    createNewCache();
    assertGarbageFilesAllDeleted();
  }

  @Test public void openWithInvalidBlankLineClearsDirectory() throws Exception {
    cache.close();
    generateSomeGarbageFiles();
    createJournalWithHeader(MAGIC, "1", "100", "2", "x");
    createNewCache();
    assertGarbageFilesAllDeleted();
  }

  @Test public void openWithInvalidJournalLineClearsDirectory() throws Exception {
    cache.close();
    generateSomeGarbageFiles();
    createJournal("CLEAN k1 1 1", "BOGUS");
    createNewCache();
    assertGarbageFilesAllDeleted();
    assertNull(cache.get("k1"));
  }

  @Test public void openWithInvalidFileSizeClearsDirectory() throws Exception {
    cache.close();
    generateSomeGarbageFiles();
    createJournal("CLEAN k1 0000x001 1");
    createNewCache();
    assertGarbageFilesAllDeleted();
    assertNull(cache.get("k1"));
  }

  @Test public void openWithTruncatedLineDiscardsThatLine() throws Exception {
    cache.close();
    writeFile(getCleanFile("k1", 0), "A");
    writeFile(getCleanFile("k1", 1), "B");
    Writer writer = new FileWriter(journalFile);
    writer.write(MAGIC + "\n" + VERSION_1 + "\n100\n2\n\nCLEAN k1 1 1"); // no trailing newline
    writer.close();
    createNewCache();
    assertNull(cache.get("k1"));

    // The journal is not corrupt when editing after a truncated line.
    set("k1", "C", "D");

    cache.close();
    createNewCache();
    assertValue("k1", "C", "D");
  }

  @Test public void openWithTooManyFileSizesClearsDirectory() throws Exception {
    cache.close();
    generateSomeGarbageFiles();
    createJournal("CLEAN k1 1 1 1");
    createNewCache();
    assertGarbageFilesAllDeleted();
    assertNull(cache.get("k1"));
  }

  @Test public void keyWithSpaceNotPermitted() throws Exception {
    try {
      cache.edit("my key");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void keyWithNewlineNotPermitted() throws Exception {
    try {
      cache.edit("my\nkey");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void keyWithCarriageReturnNotPermitted() throws Exception {
    try {
      cache.edit("my\rkey");
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void nullKeyThrows() throws Exception {
    try {
      cache.edit(null);
      fail();
    } catch (NullPointerException expected) {
    }
  }

  @Test public void createNewEntryWithTooFewValuesFails() throws Exception {
    DiskLruCache.Editor creator = cache.edit("k1");
    setString(creator, 1, "A");
    try {
      creator.commit();
      fail();
    } catch (IllegalStateException expected) {
    }

    assertFalse(getCleanFile("k1", 0).exists());
    assertFalse(getCleanFile("k1", 1).exists());
    assertFalse(getDirtyFile("k1", 0).exists());
    assertFalse(getDirtyFile("k1", 1).exists());
    assertNull(cache.get("k1"));

    DiskLruCache.Editor creator2 = cache.edit("k1");
    setString(creator2, 0, "B");
    setString(creator2, 1, "C");
    creator2.commit();
  }

  @Test public void revertWithTooFewValues() throws Exception {
    DiskLruCache.Editor creator = cache.edit("k1");
    setString(creator, 1, "A");
    creator.abort();
    assertFalse(getCleanFile("k1", 0).exists());
    assertFalse(getCleanFile("k1", 1).exists());
    assertFalse(getDirtyFile("k1", 0).exists());
    assertFalse(getDirtyFile("k1", 1).exists());
    assertNull(cache.get("k1"));
  }

  @Test public void updateExistingEntryWithTooFewValuesReusesPreviousValues() throws Exception {
    DiskLruCache.Editor creator = cache.edit("k1");
    setString(creator, 0, "A");
    setString(creator, 1, "B");
    creator.commit();

    DiskLruCache.Editor updater = cache.edit("k1");
    setString(updater, 0, "C");
    updater.commit();

    DiskLruCache.Snapshot snapshot = cache.get("k1");
    assertSnapshotValue(snapshot, "C", 0);
    assertSnapshotValue(snapshot, "B", 1);
    snapshot.close();
  }

  @Test public void growMaxSize() throws Exception {
    cache.close();
    createNewCacheWithSize(10);
    set("a", "a", "aaa"); // size 4
    set("b", "bb", "bbbb"); // size 6
    cache.setMaxSize(20);
    set("c", "c", "c"); // size 12
    assertEquals(12, cache.size());
  }

  @Test public void shrinkMaxSizeEvicts() throws Exception {
    cache.close();
    createNewCacheWithSize(20);
    set("a", "a", "aaa"); // size 4
    set("b", "bb", "bbbb"); // size 6
    set("c", "c", "c"); // size 12
    cache.setMaxSize(10);
    assertEquals(1, executor.jobs.size());
  }

  @Test public void evictOnInsert() throws Exception {
    cache.close();
    createNewCacheWithSize(10);

    set("a", "a", "aaa"); // size 4
    set("b", "bb", "bbbb"); // size 6
    assertEquals(10, cache.size());

    // Cause the size to grow to 12 should evict 'A'.
    set("c", "c", "c");
    cache.flush();
    assertEquals(8, cache.size());
    assertAbsent("a");
    assertValue("b", "bb", "bbbb");
    assertValue("c", "c", "c");

    // Causing the size to grow to 10 should evict nothing.
    set("d", "d", "d");
    cache.flush();
    assertEquals(10, cache.size());
    assertAbsent("a");
    assertValue("b", "bb", "bbbb");
    assertValue("c", "c", "c");
    assertValue("d", "d", "d");

    // Causing the size to grow to 18 should evict 'B' and 'C'.
    set("e", "eeee", "eeee");
    cache.flush();
    assertEquals(10, cache.size());
    assertAbsent("a");
    assertAbsent("b");
    assertAbsent("c");
    assertValue("d", "d", "d");
    assertValue("e", "eeee", "eeee");
  }

  @Test public void evictOnUpdate() throws Exception {
    cache.close();
    createNewCacheWithSize(10);

    set("a", "a", "aa"); // size 3
    set("b", "b", "bb"); // size 3
    set("c", "c", "cc"); // size 3
    assertEquals(9, cache.size());

    // Causing the size to grow to 11 should evict 'A'.
    set("b", "b", "bbbb");
    cache.flush();
    assertEquals(8, cache.size());
    assertAbsent("a");
    assertValue("b", "b", "bbbb");
    assertValue("c", "c", "cc");
  }

  @Test public void evictionHonorsLruFromCurrentSession() throws Exception {
    cache.close();
    createNewCacheWithSize(10);
    set("a", "a", "a");
    set("b", "b", "b");
    set("c", "c", "c");
    set("d", "d", "d");
    set("e", "e", "e");
    cache.get("b").close(); // 'B' is now least recently used.

    // Causing the size to grow to 12 should evict 'A'.
    set("f", "f", "f");
    // Causing the size to grow to 12 should evict 'C'.
    set("g", "g", "g");
    cache.flush();
    assertEquals(10, cache.size());
    assertAbsent("a");
    assertValue("b", "b", "b");
    assertAbsent("c");
    assertValue("d", "d", "d");
    assertValue("e", "e", "e");
    assertValue("f", "f", "f");
  }

  @Test public void evictionHonorsLruFromPreviousSession() throws Exception {
    set("a", "a", "a");
    set("b", "b", "b");
    set("c", "c", "c");
    set("d", "d", "d");
    set("e", "e", "e");
    set("f", "f", "f");
    cache.get("b").close(); // 'B' is now least recently used.
    assertEquals(12, cache.size());
    cache.close();
    createNewCacheWithSize(10);

    set("g", "g", "g");
    cache.flush();
    assertEquals(10, cache.size());
    assertAbsent("a");
    assertValue("b", "b", "b");
    assertAbsent("c");
    assertValue("d", "d", "d");
    assertValue("e", "e", "e");
    assertValue("f", "f", "f");
    assertValue("g", "g", "g");
  }

  @Test public void cacheSingleEntryOfSizeGreaterThanMaxSize() throws Exception {
    cache.close();
    createNewCacheWithSize(10);
    set("a", "aaaaa", "aaaaaa"); // size=11
    cache.flush();
    assertAbsent("a");
  }

  @Test public void cacheSingleValueOfSizeGreaterThanMaxSize() throws Exception {
    cache.close();
    createNewCacheWithSize(10);
    set("a", "aaaaaaaaaaa", "a"); // size=12
    cache.flush();
    assertAbsent("a");
  }

  @Test public void constructorDoesNotAllowZeroCacheSize() throws Exception {
    try {
      DiskLruCache.create(cacheDir, appVersion, 2, 0);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void constructorDoesNotAllowZeroValuesPerEntry() throws Exception {
    try {
      DiskLruCache.create(cacheDir, appVersion, 0, 10);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test public void removeAbsentElement() throws Exception {
    cache.remove("a");
  }

  @Test public void readingTheSameStreamMultipleTimes() throws Exception {
    set("a", "a", "b");
    DiskLruCache.Snapshot snapshot = cache.get("a");
    assertSame(snapshot.getSource(0), snapshot.getSource(0));
    snapshot.close();
  }

  @Test public void rebuildJournalOnRepeatedReads() throws Exception {
    set("a", "a", "a");
    set("b", "b", "b");
    while (executor.jobs.isEmpty()) {
      assertValue("a", "a", "a");
      assertValue("b", "b", "b");
    }
  }

  @Test public void rebuildJournalOnRepeatedEdits() throws Exception {
    while (executor.jobs.isEmpty()) {
      set("a", "a", "a");
      set("b", "b", "b");
    }
    executor.jobs.removeFirst().run();

    // Sanity check that a rebuilt journal behaves normally.
    assertValue("a", "a", "a");
    assertValue("b", "b", "b");
  }

  /** @see <a href="https://github.com/JakeWharton/DiskLruCache/issues/28">Issue #28</a> */
  @Test public void rebuildJournalOnRepeatedReadsWithOpenAndClose() throws Exception {
    set("a", "a", "a");
    set("b", "b", "b");
    while (executor.jobs.isEmpty()) {
      assertValue("a", "a", "a");
      assertValue("b", "b", "b");
      cache.close();
      createNewCache();
    }
  }

  /** @see <a href="https://github.com/JakeWharton/DiskLruCache/issues/28">Issue #28</a> */
  @Test public void rebuildJournalOnRepeatedEditsWithOpenAndClose() throws Exception {
    while (executor.jobs.isEmpty()) {
      set("a", "a", "a");
      set("b", "b", "b");
      cache.close();
      createNewCache();
    }
  }

  @Test public void restoreBackupFile() throws Exception {
    DiskLruCache.Editor creator = cache.edit("k1");
    setString(creator, 0, "ABC");
    setString(creator, 1, "DE");
    creator.commit();
    cache.close();

    assertTrue(journalFile.renameTo(journalBkpFile));
    assertFalse(journalFile.exists());

    createNewCache();

    DiskLruCache.Snapshot snapshot = cache.get("k1");
    assertSnapshotValue(snapshot, "ABC", 0);
    assertSnapshotValue(snapshot, "DE", 1);

    assertFalse(journalBkpFile.exists());
    assertTrue(journalFile.exists());
  }

  @Test public void journalFileIsPreferredOverBackupFile() throws Exception {
    DiskLruCache.Editor creator = cache.edit("k1");
    setString(creator, 0, "ABC");
    setString(creator, 1, "DE");
    creator.commit();
    cache.flush();

    copyFile(journalFile, journalBkpFile);

    creator = cache.edit("k2");
    setString(creator, 0, "F");
    setString(creator, 1, "GH");
    creator.commit();
    cache.close();

    assertTrue(journalFile.exists());
    assertTrue(journalBkpFile.exists());

    createNewCache();

    DiskLruCache.Snapshot snapshotA = cache.get("k1");
    assertSnapshotValue(snapshotA, "ABC", 0);
    assertSnapshotValue(snapshotA, "DE", 1);

    DiskLruCache.Snapshot snapshotB = cache.get("k2");
    assertSnapshotValue(snapshotB, "F", 0);
    assertSnapshotValue(snapshotB, "GH", 1);

    assertFalse(journalBkpFile.exists());
    assertTrue(journalFile.exists());
  }

  @Test public void openCreatesDirectoryIfNecessary() throws Exception {
    cache.close();
    File dir = tempDir.newFolder("testOpenCreatesDirectoryIfNecessary");
    cache = DiskLruCache.create(dir, appVersion, 2, Integer.MAX_VALUE);
    set("a", "a", "a");
    assertTrue(new File(dir, "a.0").exists());
    assertTrue(new File(dir, "a.1").exists());
    assertTrue(new File(dir, "journal").exists());
  }

  @Test public void fileDeletedExternally() throws Exception {
    set("a", "a", "a");
    getCleanFile("a", 1).delete();
    assertNull(cache.get("a"));
  }

  @Test public void editSameVersion() throws Exception {
    set("a", "a", "a");
    DiskLruCache.Snapshot snapshot = cache.get("a");
    DiskLruCache.Editor editor = snapshot.edit();
    setString(editor, 1, "a2");
    editor.commit();
    assertValue("a", "a", "a2");
  }

  @Test public void editSnapshotAfterChangeAborted() throws Exception {
    set("a", "a", "a");
    DiskLruCache.Snapshot snapshot = cache.get("a");
    DiskLruCache.Editor toAbort = snapshot.edit();
    setString(toAbort, 0, "b");
    toAbort.abort();
    DiskLruCache.Editor editor = snapshot.edit();
    setString(editor, 1, "a2");
    editor.commit();
    assertValue("a", "a", "a2");
  }

  @Test public void editSnapshotAfterChangeCommitted() throws Exception {
    set("a", "a", "a");
    DiskLruCache.Snapshot snapshot = cache.get("a");
    DiskLruCache.Editor toAbort = snapshot.edit();
    setString(toAbort, 0, "b");
    toAbort.commit();
    assertNull(snapshot.edit());
  }

  @Test public void editSinceEvicted() throws Exception {
    cache.close();
    createNewCacheWithSize(10);
    set("a", "aa", "aaa"); // size 5
    DiskLruCache.Snapshot snapshot = cache.get("a");
    set("b", "bb", "bbb"); // size 5
    set("c", "cc", "ccc"); // size 5; will evict 'A'
    cache.flush();
    assertNull(snapshot.edit());
  }

  @Test public void editSinceEvictedAndRecreated() throws Exception {
    cache.close();
    createNewCacheWithSize(10);
    set("a", "aa", "aaa"); // size 5
    DiskLruCache.Snapshot snapshot = cache.get("a");
    set("b", "bb", "bbb"); // size 5
    set("c", "cc", "ccc"); // size 5; will evict 'A'
    set("a", "a", "aaaa"); // size 5; will evict 'B'
    cache.flush();
    assertNull(snapshot.edit());
  }

  /** @see <a href="https://github.com/JakeWharton/DiskLruCache/issues/2">Issue #2</a> */
  @Test public void aggressiveClearingHandlesWrite() throws Exception {
    tempDir.delete();
    set("a", "a", "a");
    assertValue("a", "a", "a");
  }

  /** @see <a href="https://github.com/JakeWharton/DiskLruCache/issues/2">Issue #2</a> */
  @Test public void aggressiveClearingHandlesEdit() throws Exception {
    set("a", "a", "a");
    DiskLruCache.Editor a = cache.get("a").edit();
    tempDir.delete();
    setString(a, 1, "a2");
    a.commit();
  }

  @Test public void removeHandlesMissingFile() throws Exception {
    set("a", "a", "a");
    getCleanFile("a", 0).delete();
    cache.remove("a");
  }

  /** @see <a href="https://github.com/JakeWharton/DiskLruCache/issues/2">Issue #2</a> */
  @Test public void aggressiveClearingHandlesPartialEdit() throws Exception {
    set("a", "a", "a");
    set("b", "b", "b");
    DiskLruCache.Editor a = cache.get("a").edit();
    setString(a, 0, "a1");
    tempDir.delete();
    setString(a, 1, "a2");
    a.commit();
    assertNull(cache.get("a"));
  }

  /** @see <a href="https://github.com/JakeWharton/DiskLruCache/issues/2">Issue #2</a> */
  @Test public void aggressiveClearingHandlesRead() throws Exception {
    tempDir.delete();
    assertNull(cache.get("a"));
  }

  /**
   * We had a long-lived bug where {@link DiskLruCache#trimToSize} could
   * infinite loop if entries being edited required deletion for the operation
   * to complete.
   */
  @Test public void trimToSizeWithActiveEdit() throws Exception {
    set("a", "a1234", "a1234");
    DiskLruCache.Editor a = cache.edit("a");
    setString(a, 0, "a123");

    cache.setMaxSize(8); // Smaller than the sum of active edits!
    cache.flush(); // Force trimToSize().
    assertEquals(0, cache.size());
    assertNull(cache.get("a"));

    // After the edit is completed, its entry is still gone.
    setString(a, 1, "a1");
    a.commit();
    assertAbsent("a");
    assertEquals(0, cache.size());
  }

  @Test public void evictAll() throws Exception {
    set("a", "a", "a");
    set("b", "b", "b");
    cache.evictAll();
    assertEquals(0, cache.size());
    assertAbsent("a");
    assertAbsent("b");
  }

  @Test public void evictAllWithPartialCreate() throws Exception {
    DiskLruCache.Editor a = cache.edit("a");
    setString(a, 0, "a1");
    setString(a, 1, "a2");
    cache.evictAll();
    assertEquals(0, cache.size());
    a.commit();
    assertAbsent("a");
  }

  @Test public void evictAllWithPartialEditDoesNotStoreAValue() throws Exception {
    set("a", "a", "a");
    DiskLruCache.Editor a = cache.edit("a");
    setString(a, 0, "a1");
    setString(a, 1, "a2");
    cache.evictAll();
    assertEquals(0, cache.size());
    a.commit();
    assertAbsent("a");
  }

  @Test public void evictAllDoesntInterruptPartialRead() throws Exception {
    set("a", "a", "a");
    DiskLruCache.Snapshot a = cache.get("a");
    assertSnapshotValue(a, "a", 0);
    cache.evictAll();
    assertEquals(0, cache.size());
    assertAbsent("a");
    assertSnapshotValue(a, "a", 1);
    a.close();
  }

  @Test public void editSnapshotAfterEvictAllReturnsNullDueToStaleValue() throws Exception {
    set("a", "a", "a");
    DiskLruCache.Snapshot a = cache.get("a");
    cache.evictAll();
    assertEquals(0, cache.size());
    assertAbsent("a");
    assertNull(a.edit());
    a.close();
  }

  @Test public void iterator() throws Exception {
    set("a", "a1", "a2");
    set("b", "b1", "b2");
    set("c", "c1", "c2");
    Iterator<DiskLruCache.Snapshot> iterator = cache.snapshots();

    assertTrue(iterator.hasNext());
    DiskLruCache.Snapshot a = iterator.next();
    assertEquals("a", a.key());
    assertSnapshotValue(a, "a1", 0);
    assertSnapshotValue(a, "a2", 1);
    a.close();

    assertTrue(iterator.hasNext());
    DiskLruCache.Snapshot b = iterator.next();
    assertEquals("b", b.key());
    assertSnapshotValue(b, "b1", 0);
    assertSnapshotValue(b, "b2", 1);
    b.close();

    assertTrue(iterator.hasNext());
    DiskLruCache.Snapshot c = iterator.next();
    assertEquals("c", c.key());
    assertSnapshotValue(c, "c1", 0);
    assertSnapshotValue(c, "c2", 1);
    c.close();

    assertFalse(iterator.hasNext());
    try {
      iterator.next();
      fail();
    } catch (NoSuchElementException expected) {
    }
  }

  @Test public void iteratorElementsAddedDuringIterationAreOmitted() throws Exception {
    set("a", "a1", "a2");
    set("b", "b1", "b2");
    Iterator<DiskLruCache.Snapshot> iterator = cache.snapshots();

    DiskLruCache.Snapshot a = iterator.next();
    assertEquals("a", a.key());
    a.close();

    set("c", "c1", "c2");

    DiskLruCache.Snapshot b = iterator.next();
    assertEquals("b", b.key());
    b.close();

    assertFalse(iterator.hasNext());
  }

  @Test public void iteratorElementsUpdatedDuringIterationAreUpdated() throws Exception {
    set("a", "a1", "a2");
    set("b", "b1", "b2");
    Iterator<DiskLruCache.Snapshot> iterator = cache.snapshots();

    DiskLruCache.Snapshot a = iterator.next();
    assertEquals("a", a.key());
    a.close();

    set("b", "b3", "b4");

    DiskLruCache.Snapshot b = iterator.next();
    assertEquals("b", b.key());
    assertSnapshotValue(b, "b3", 0);
    assertSnapshotValue(b, "b4", 1);
    b.close();
  }

  @Test public void iteratorElementsRemovedDuringIterationAreOmitted() throws Exception {
    set("a", "a1", "a2");
    set("b", "b1", "b2");
    Iterator<DiskLruCache.Snapshot> iterator = cache.snapshots();

    cache.remove("b");

    DiskLruCache.Snapshot a = iterator.next();
    assertEquals("a", a.key());
    a.close();

    assertFalse(iterator.hasNext());
  }

  @Test public void iteratorRemove() throws Exception {
    set("a", "a1", "a2");
    Iterator<DiskLruCache.Snapshot> iterator = cache.snapshots();

    DiskLruCache.Snapshot a = iterator.next();
    a.close();
    iterator.remove();

    assertEquals(null, cache.get("a"));
  }

  @Test public void iteratorRemoveBeforeNext() throws Exception {
    set("a", "a1", "a2");
    Iterator<DiskLruCache.Snapshot> iterator = cache.snapshots();
    try {
      iterator.remove();
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test public void iteratorRemoveOncePerCallToNext() throws Exception {
    set("a", "a1", "a2");
    Iterator<DiskLruCache.Snapshot> iterator = cache.snapshots();

    DiskLruCache.Snapshot a = iterator.next();
    iterator.remove();
    a.close();

    try {
      iterator.remove();
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test public void cacheClosedTruncatesIterator() throws Exception {
    set("a", "a1", "a2");
    Iterator<DiskLruCache.Snapshot> iterator = cache.snapshots();
    cache.close();
    assertFalse(iterator.hasNext());
  }

  private void assertJournalEquals(String... expectedBodyLines) throws Exception {
    List<String> expectedLines = new ArrayList<>();
    expectedLines.add(MAGIC);
    expectedLines.add(VERSION_1);
    expectedLines.add("100");
    expectedLines.add("2");
    expectedLines.add("");
    expectedLines.addAll(Arrays.asList(expectedBodyLines));
    assertEquals(expectedLines, readJournalLines());
  }

  private void createJournal(String... bodyLines) throws Exception {
    createJournalWithHeader(MAGIC, VERSION_1, "100", "2", "", bodyLines);
  }

  private void createJournalWithHeader(String magic, String version, String appVersion,
      String valueCount, String blank, String... bodyLines) throws Exception {
    Writer writer = new FileWriter(journalFile);
    writer.write(magic + "\n");
    writer.write(version + "\n");
    writer.write(appVersion + "\n");
    writer.write(valueCount + "\n");
    writer.write(blank + "\n");
    for (String line : bodyLines) {
      writer.write(line);
      writer.write('\n');
    }
    writer.close();
  }

  private List<String> readJournalLines() throws Exception {
    List<String> result = new ArrayList<>();
    BufferedReader reader = new BufferedReader(new FileReader(journalFile));
    String line;
    while ((line = reader.readLine()) != null) {
      result.add(line);
    }
    reader.close();
    return result;
  }

  private File getCleanFile(String key, int index) {
    return new File(cacheDir, key + "." + index);
  }

  private File getDirtyFile(String key, int index) {
    return new File(cacheDir, key + "." + index + ".tmp");
  }

  private static String readFile(File file) throws Exception {
    Reader reader = new FileReader(file);
    StringWriter writer = new StringWriter();
    char[] buffer = new char[1024];
    int count;
    while ((count = reader.read(buffer)) != -1) {
      writer.write(buffer, 0, count);
    }
    reader.close();
    return writer.toString();
  }

  public static void writeFile(File file, String content) throws Exception {
    FileWriter writer = new FileWriter(file);
    writer.write(content);
    writer.close();
  }

  private static void assertInoperable(DiskLruCache.Editor editor) throws Exception {
    try {
      setString(editor, 0, "A");
      fail();
    } catch (IllegalStateException expected) {
    }
    try {
      editor.newSource(0);
      fail();
    } catch (IllegalStateException expected) {
    }
    try {
      editor.newSink(0);
      fail();
    } catch (IllegalStateException expected) {
    }
    try {
      editor.commit();
      fail();
    } catch (IllegalStateException expected) {
    }
    try {
      editor.abort();
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  private void generateSomeGarbageFiles() throws Exception {
    File dir1 = new File(cacheDir, "dir1");
    File dir2 = new File(dir1, "dir2");
    writeFile(getCleanFile("g1", 0), "A");
    writeFile(getCleanFile("g1", 1), "B");
    writeFile(getCleanFile("g2", 0), "C");
    writeFile(getCleanFile("g2", 1), "D");
    writeFile(getCleanFile("g2", 1), "D");
    writeFile(new File(cacheDir, "otherFile0"), "E");
    dir1.mkdir();
    dir2.mkdir();
    writeFile(new File(dir2, "otherFile1"), "F");
  }

  private void assertGarbageFilesAllDeleted() throws Exception {
    assertFalse(getCleanFile("g1", 0).exists());
    assertFalse(getCleanFile("g1", 1).exists());
    assertFalse(getCleanFile("g2", 0).exists());
    assertFalse(getCleanFile("g2", 1).exists());
    assertFalse(new File(cacheDir, "otherFile0").exists());
    assertFalse(new File(cacheDir, "dir1").exists());
  }

  private void set(String key, String value0, String value1) throws Exception {
    DiskLruCache.Editor editor = cache.edit(key);
    setString(editor, 0, value0);
    setString(editor, 1, value1);
    editor.commit();
  }

  public static void setString(DiskLruCache.Editor editor, int index, String value) throws IOException {
    BufferedSink writer = Okio.buffer(editor.newSink(index));
    writer.writeUtf8(value);
    writer.close();
  }

  private void assertAbsent(String key) throws Exception {
    DiskLruCache.Snapshot snapshot = cache.get(key);
    if (snapshot != null) {
      snapshot.close();
      fail();
    }
    assertFalse(getCleanFile(key, 0).exists());
    assertFalse(getCleanFile(key, 1).exists());
    assertFalse(getDirtyFile(key, 0).exists());
    assertFalse(getDirtyFile(key, 1).exists());
  }

  private void assertValue(String key, String value0, String value1) throws Exception {
    DiskLruCache.Snapshot snapshot = cache.get(key);
    assertSnapshotValue(snapshot, value0, 0);
    assertSnapshotValue(snapshot, value1, 1);
    assertTrue(getCleanFile(key, 0).exists());
    assertTrue(getCleanFile(key, 1).exists());
    snapshot.close();
  }

  private void assertSnapshotValue(DiskLruCache.Snapshot snapshot, String value, int index)
      throws IOException {
    assertEquals(value, sourceAsString(snapshot.getSource(index)));
    assertEquals(value.length(), snapshot.getLength(index));
  }

  private String sourceAsString(Source source) throws IOException {
    return source != null ? Okio.buffer(source).readUtf8() : null;
  }

  private void copyFile(File from, File to) throws IOException {
    Source source = Okio.source(from);
    BufferedSink sink = Okio.buffer(Okio.sink(to));
    sink.writeAll(source);
    source.close();
    sink.close();
  }

  private static class TestExecutor implements Executor {
    final Deque<Runnable> jobs = new ArrayDeque<>();

    @Override public void execute(Runnable command) {
      jobs.addLast(command);
    }
  }
}
