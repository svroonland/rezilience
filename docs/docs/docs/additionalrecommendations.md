---
layout: docs
title: Additional resiliency
permalink: docs/additional_resiliency/
---

# Additional resiliency recommendations
The following additional resiliency policies are not included in this library. Some because they are standard ZIO functionality. They can be applied in combination with `rezilience` policies.

* Add a cache to speed up response time and provide an alternative in case of failures. `rezilience` does not provide a cache since it is a specialized topic. A library like [scalacache](https://cb372.github.io/scalacache/docs/modes.html) offers ZIO integration via cats-effect interop.

* Add a fallback using `ZIO#orElse`, a 'degraded mode' alternative response when a resource is not available. You usually want to do this as the outermost decorator.
