---
layout: docs
title: zio-config integration
permalink: docs/zio-config/
---

# zio-config Integration

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

```scala mdoc:silent
import nl.vroste.rezilience._
import nl.vroste.rezilience.config._

import com.typesafe.config.ConfigFactory
import zio.ZIO
import zio.config.typesafe.TypesafeConfigProvider

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

val configProvider = TypesafeConfigProvider.fromTypesafeConfig(config.getConfig("my-circuit-breaker"))

val program = ZIO.scoped {
    for {
        config <- ZIO.config[CircuitBreakerConfig]
        cb <- CircuitBreaker.fromConfig(config)
        _ <- cb(ZIO.unit)
      } yield ()
}

program.provideLayer(zio.Runtime.setConfigProvider(configProvider) ++ zio.Scope.default)
```

Typically you would create a top-level `ApplicationConfig` where one of the (nested) fields is a `CircuitBreakerConfig` for a specific Circuit Breaker instance, along with your other application configs and rezilience policies. See the [ZIO documentation](https://zio.dev/reference/configuration/) on Configuration for more information on how to integrate this in your application.

## Configuration reference

# Circuit Breaker

## Configuration Details
FieldName|Format                     |Description|Sources|
---      |---                        |---        |---    |
|[all-of](fielddescriptions)|           |       |
### Field Descriptions
FieldName                             |Format                         |Description|Sources|
---                                   |---                            |---        |---    |
[tripping-strategy](tripping-strategy)|[any-one-of](tripping-strategy)|           |       |
[reset-schedule](reset-schedule)      |[all-of](reset-schedule)       |           |       |
### tripping-strategy
FieldName   |Format                       |Description        |Sources|
---         |---                          |---                |---    |
max-failures|primitive                    |an integer property|       |
|[all-of](fielddescriptions-1)|                   |       |
### Field Descriptions
FieldName             |Format                           |Description       |Sources|
---                   |---                              |---               |---    |
failure-rate-threshold|primitive                        |a decimal property|       |
|[any-one-of](fielddescriptions-4)|                  |       |
|[any-one-of](fielddescriptions-3)|                  |       |
|[any-one-of](fielddescriptions-2)|                  |       |
### Field Descriptions
FieldName      |Format   |Description        |Sources|
---            |---      |---                |---    |
sample-duration|primitive|a duration property|       |
|primitive|a constant property|       |
### Field Descriptions
FieldName     |Format   |Description        |Sources|
---           |---      |---                |---    |
min-throughput|primitive|an integer property|       |
|primitive|a constant property|       |
### Field Descriptions
FieldName        |Format   |Description        |Sources|
---              |---      |---                |---    |
nr-sample-buckets|primitive|an integer property|       |
|primitive|a constant property|       |
### reset-schedule
FieldName|Format                           |Description|Sources|
---      |---                              |---        |---    |
|[any-one-of](fielddescriptions-3)|           |       |
|[any-one-of](fielddescriptions-2)|           |       |
|[any-one-of](fielddescriptions-1)|           |       |
### Field Descriptions
FieldName|Format   |Description        |Sources|
---      |---      |---                |---    |
min      |primitive|a duration property|       |
|primitive|a constant property|       |
### Field Descriptions
FieldName|Format   |Description        |Sources|
---      |---      |---                |---    |
max      |primitive|a duration property|       |
|primitive|a constant property|       |
### Field Descriptions
FieldName|Format   |Description        |Sources|
---      |---      |---                |---    |
factor   |primitive|a decimal property |       |
|primitive|a constant property|       |

# RateLimiter

## Configuration Details
FieldName|Format                     |Description|Sources|
---      |---                        |---        |---    |
|[all-of](fielddescriptions)|           |       |
### Field Descriptions
FieldName|Format   |Description        |Sources|
---      |---      |---                |---    |
max      |primitive|an integer property|       |
interval |primitive|a duration property|       |

# Bulkhead

## Configuration Details
FieldName|Format                     |Description|Sources|
---      |---                        |---        |---    |
|[all-of](fielddescriptions)|           |       |
### Field Descriptions
FieldName          |Format                           |Description        |Sources|
---                |---                              |---                |---    |
max-in-flight-calls|primitive                        |an integer property|       |
|[any-one-of](fielddescriptions-1)|                   |       |
### Field Descriptions
FieldName   |Format   |Description        |Sources|
---         |---      |---                |---    |
max-queueing|primitive|an integer property|       |
|primitive|a constant property|       |

# Retry

## Configuration Details
FieldName|Format                     |Description|Sources|
---      |---                        |---        |---    |
|[all-of](fielddescriptions)|           |       |
### Field Descriptions
FieldName  |Format                           |Description        |Sources|
---        |---                              |---                |---    |
min-delay  |primitive                        |a duration property|       |
max-delay  |primitive                        |a duration property|       |
|[any-one-of](fielddescriptions-3)|                   |       |
|[any-one-of](fielddescriptions-2)|                   |       |
max-retries|primitive                        |an integer property|       |
|[any-one-of](fielddescriptions-1)|                   |       |
### Field Descriptions
FieldName|Format   |Description        |Sources|
---      |---      |---                |---    |
factor   |primitive|a decimal property |       |
|primitive|a constant property|       |
### Field Descriptions
FieldName        |Format   |Description        |Sources|
---              |---      |---                |---    |
retry-immediately|primitive|a boolean property |       |
|primitive|a constant property|       |
### Field Descriptions
FieldName|Format   |Description        |Sources|
---      |---      |---                |---    |
jitter   |primitive|a decimal property |       |
|primitive|a constant property|       |

# Timeout

## Configuration Details
FieldName|Format   |Description        |Sources|
---      |---      |---                |---    |
timeout  |primitive|a duration property|       |