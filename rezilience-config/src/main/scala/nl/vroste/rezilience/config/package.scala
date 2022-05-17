package nl.vroste.rezilience

package object config
    extends CircuitBreakerFromConfigSyntax
    with RateLimiterFromConfigSyntax
    with BulkheadFromConfigSyntax
    with TimeoutFromConfigSyntax
    with RetryFromConfigSyntax {}
