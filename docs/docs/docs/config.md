
---
layout: docs
title: zio-config integration
permalink: docs/config/
---

Rezilience has an optional module `rezilience-config` for integration with `zio-config` to create policies from config files.

Add to your build.sbt:

```scala
libraryDependencies += "nl.vroste" %% "rezilience-config" % "<version>"
```

and add the following import:

```scala
import nl.vroste.rezilience.config._
```

Now you can use the `fromConfig` method on any of the rezilience policies like so:

```scala
import nl.vroste.rezilience._
import nl.vroste.rezilience.config._

import com.typesafe.config.ConfigFactory
import zio.ZIO
import zio.config.typesafe.TypesafeConfigSource

// Replace with your favorite zio-config integration
val config = ConfigFactory.parseString(s"""
                                          | my-circuit-breaker {
                                          |  tripping-strategy {
                                          |    failure-rate-threshold = 0.75
                                          |    sample-duration = 2 seconds
                                          |    min-throughput = 1
                                          |    nr-sample-buckets = 2
                                          |  }
                                          |  
                                          |  reset-schedule {
                                          |    min = 3 seconds
                                          |  }
                                          | }
                                          |""".stripMargin)

val configSource = TypesafeConfigSource.fromTypesafeConfig(config.getConfig("my-circuit-breaker"))

ZIO
  .fromEither(configSource)
  .toManaged_
  .flatMap(CircuitBreaker.fromConfig(_))
  .use { cb =>
    cb(ZIO.unit)
  }

```

## Configuration reference


## Circuit Breaker

### Configuration Details


FieldName|Format                     |Description|Sources|
---      |---                        |---        |---    |
|[all-of](fielddescriptions)|           |       |

#### Field Descriptions

FieldName                             |Format                         |Description|Sources|
---                                   |---                            |---        |---    |
[tripping-strategy](tripping-strategy)|[any-one-of](tripping-strategy)|           |       |
[reset-schedule](reset-schedule)      |[all-of](reset-schedule)       |           |       |

#### tripping-strategy

FieldName   |Format                       |Description      |Sources|
---         |---                          |---              |---    |
max-failures|primitive                    |value of type int|       |
|[all-of](fielddescriptions-1)|                 |       |

#### Field Descriptions

FieldName             |Format   |Description                                |Sources|
---                   |---      |---                                        |---    |
failure-rate-threshold|primitive|value of type double                       |       |
sample-duration       |primitive|value of type duration, default value: PT1M|       |
min-throughput        |primitive|value of type int, default value: 10       |       |
nr-sample-buckets     |primitive|value of type int, default value: 10       |       |

#### reset-schedule

FieldName|Format   |Description                                |Sources|
---      |---      |---                                        |---    |
min      |primitive|value of type duration, default value: PT1S|       |
max      |primitive|value of type duration, default value: PT1M|       |
factor   |primitive|value of type double, default value: 2.0   |       |


## RateLimiter

### Configuration Details


FieldName|Format                     |Description|Sources|
---      |---                        |---        |---    |
|[all-of](fielddescriptions)|           |       |

#### Field Descriptions

FieldName|Format   |Description           |Sources|
---      |---      |---                   |---    |
max      |primitive|value of type int     |       |
interval |primitive|value of type duration|       |


## Bulkhead

### Configuration Details


FieldName|Format                     |Description|Sources|
---      |---                        |---        |---    |
|[all-of](fielddescriptions)|           |       |

#### Field Descriptions

FieldName          |Format   |Description                         |Sources|
---                |---      |---                                 |---    |
max-in-flight-calls|primitive|value of type int                   |       |
max-queueing       |primitive|value of type int, default value: 32|       |


## Retry

### Configuration Details


FieldName|Format                     |Description|Sources|
---      |---                        |---        |---    |
|[all-of](fielddescriptions)|           |       |

#### Field Descriptions

FieldName        |Format   |Description                                |Sources|
---              |---      |---                                        |---    |
min-delay        |primitive|value of type duration                     |       |
max-delay        |primitive|value of type duration, optional value     |       |
factor           |primitive|value of type double, default value: 2.0   |       |
retry-immediately|primitive|value of type boolean, default value: false|       |
max-retries      |primitive|value of type int, optional value          |       |
jitter           |primitive|value of type double, default value: 0.0   |       |


## Timeout

### Configuration Details


FieldName|Format   |Description           |Sources|
---      |---      |---                   |---    |
timeout  |primitive|value of type duration|       |