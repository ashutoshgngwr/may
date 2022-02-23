# May

**A deliberately simple persistent key-value store for Android**. It uses
[SQLite databases](https://developer.android.com/training/data-storage/sqlite)
to store its data and [Kryo](https://github.com/EsotericSoftware/kryo) binary
serialization to (de)serialize objects.

## Implementation

Each entry has a string key and a value object. Typically, values are simple
data containers, i.e., POJO or Kotlin data classes. Each May datastore stores
its data in a single table inside its corresponding SQLite database. It uses
key's [hash
code](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/lang/String.html#hashCode())
as the table primary key to optimise SQLite look-up performance. It uses [thread
local](https://docs.oracle.com/javase/7/docs/api/java/lang/ThreadLocal.html)
[Kryo](https://github.com/EsotericSoftware/kryo) instances to (de)serialize
values and store them as blobs in the SQLite database.

## Install

Grab the latest version from Maven Central at
[`io.github.ashutoshgngwr:may`](https://repo1.maven.org/maven2/io/github/ashutoshgngwr/may/).

```gradle
implementation 'io.github.ashutoshgngwr:may:0.2.1'
```

## Usage

Usually, the clients **should not create more than one instance of May per
datastore.** SQLite can only handle write operations from one thread at a time.
Opening multiple May instances on the same datastore may lead to its corruption.

```kotlin
val may = May.openOrCreateDatastore("path/to/my.db")

// persist value
may.put("key", "value")

// check if key exists in the store
may.contains("key")

// retrieve value
val value: String? = may.getAs<String>("key")

// remove value
val wasRemoved = may.remove("key")

// list 10 keys by prefix in ascending order skipping the first 5 that match.
val keys = may.keys("prefix/", offset = 5, limit = 10)

// close datastore
may.close()
```

### Matching keys using a prefix

May supports matching keys using a prefix for
[find-keys](https://github.com/ashutoshgngwr/may/blob/d18b6a0b63f35229f1747e5ff083b60499b018fe/may/src/main/java/io/github/ashutoshgngwr/may/May.kt#L217),
[get-all](https://github.com/ashutoshgngwr/may/blob/d18b6a0b63f35229f1747e5ff083b60499b018fe/may/src/main/java/io/github/ashutoshgngwr/may/May.kt#L152)
and
[remove-all](https://github.com/ashutoshgngwr/may/blob/d18b6a0b63f35229f1747e5ff083b60499b018fe/may/src/main/java/io/github/ashutoshgngwr/may/May.kt#L281)
operations. These operations use a SQLite index on the key column to optimise
lookups.

### Multi-threading

Under the hood, May uses [reentrant read-write
locks](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/util/concurrent/locks/ReentrantReadWriteLock.html)
to synchronize all of its operations, allowing multiple concurrent reads and at
most one write at any given instant.

### SQLite Write-Ahead Logging

Use
[`May.enableWriteAheadLogging()`](https://github.com/ashutoshgngwr/may/blob/46f73a45ed7f62bd5203e3a0efee3848404d6999/may/src/main/java/io/github/ashutoshgngwr/may/May.kt#L112)
and
[`May.disableWriteAheadLogging()`](https://github.com/ashutoshgngwr/may/blob/46f73a45ed7f62bd5203e3a0efee3848404d6999/may/src/main/java/io/github/ashutoshgngwr/may/May.kt#L102)
to configure WAL on the underlying SQLite database.

## License

[Apache License, Version 2.0](LICENSE)
