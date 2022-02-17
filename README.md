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
implementation 'io.github.ashutoshgngwr:may:0.1.0'
```

## Usage

Usually, clients should not create more than a single instance of May per
datastore.

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

// close datastore; it usually should be done in Application#onDestroy lifecycle callback.
may.close()
```

## License

[Apache License, Version 2.0](LICENSE)
