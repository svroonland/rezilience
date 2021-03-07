// When the user clicks on the search box, we want to toggle the search dropdown
function displayToggleSearch(e) {
  e.preventDefault();
  e.stopPropagation();

  closeDropdownSearch(e);
  
  if (idx === null) {
    console.log("Building search index...");
    prepareIdxAndDocMap();
    console.log("Search index built.");
  }
  const dropdown = document.querySelector("#search-dropdown-content");
  if (dropdown) {
    if (!dropdown.classList.contains("show")) {
      dropdown.classList.add("show");
    }
    document.addEventListener("click", closeDropdownSearch);
    document.addEventListener("keydown", searchOnKeyDown);
    document.addEventListener("keyup", searchOnKeyUp);
  }
}

//We want to prepare the index only after clicking the search bar
var idx = null
const docMap = new Map()

function prepareIdxAndDocMap() {
  const docs = [  
    {
      "title": "Additional resiliency",
      "url": "/rezilience/docs/additional_resiliency/",
      "content": "Additional resiliency recommendations The following additional resiliency policies are not included in this library. Some because they are standard ZIO functionality. They can be applied in combination with rezilience policies. Add a cache to speed up response time and provide an alternative in case of failures. rezilience does not provide a cache since it is a specialized topic. A library like scalacache offers ZIO integration via cats-effect interop. Add a fallback using ZIO#orElse, a ‘degraded mode’ alternative response when a resource is not available. You usually want to do this as the outermost decorator."
    } ,    
    {
      "title": "Bulkhead",
      "url": "/rezilience/docs/bulkhead/",
      "content": "Bulkhead Bulkhead limits the number of concurrent calls to a system. Calls exceeding this number are queued, this helps to maximize resource usage. When the queue is full, calls are immediately rejected with a BulkheadRejection. Using a Bulkhead not only protects the external system, it also prevents queueing up of requests, which consumes resources in the calling system, by rejecting calls immediately when the queue is full. Any Bulkhead can execute any type of ZIO[R, E, A], so you can execute effects of different types while limiting concurrent usage of the same underlying resource. Usage example import zio._ import nl.vroste.rezilience._ import nl.vroste.rezilience.Bulkhead.BulkheadError // We use Throwable as error type in this example def myCallToExternalResource(someInput: String): ZIO[Any, Throwable, Int] = ??? val bulkhead: UManaged[Bulkhead] = Bulkhead.make(maxInFlightCalls = 10, maxQueueing = 32) bulkhead.use { bulkhead =&gt; val result: ZIO[Any, BulkheadError[Throwable], Int] = bulkhead(myCallToExternalResource(\"some input\")) }"
    } ,    
    {
      "title": "Circuit Breaker",
      "url": "/rezilience/docs/circuitbreaker/",
      "content": "Circuit Breaker Circuit Breaker is a reactive resilience strategy to safeguard an external system against overload. It will also prevent queueing up of calls to an already struggling system. Behavior A Circuit Breaker starts in the ‘closed’ state. All calls are passed through in this state. Any failures are counted. When too many failures have occurred, the breaker goes to the ‘open’ state. Calls made in this state will fail immediately with a CircuitBreakerOpen error. After some time, the circuit breaker will reset to the ‘half open’ state. In this state, one call can pass through. If this call succeeds, the circuit breaker goes back to the ‘closed’ state. If it fails, the breaker goes again to the ‘open’ state. CircuitBreaker uses a ZIO Schedule to determine the reset interval. By default, this is an exponential backoff schedule, so that reset intervals double with each iteration, capped at some maximum value. You can however provide any Schedule that fits your needs. Failure counting modes CircuitBreaker has two modes for counting failures: Failure Count Trip the circuit breaker when the number of consecutive failing calls exceeds some threshold. This is implemented in TrippingStrategy.failureCount Failure Rate Trip when the proportion of failing calls exceeds some threshold. The threshold and the sample period can be specified. You can specify a minimum call count to avoid tripping at very low call rates. This mode is implemented in TrippingStrategy.failureRate Custom tripping strategies can be implemented by extending TrippingStrategy. Usage example import nl.vroste.rezilience.CircuitBreaker._ import nl.vroste.rezilience._ import zio._ import zio.clock.Clock import zio.console.putStrLn import zio.duration._ object CircuitBreakerExample { // We use Throwable as error type in this example def callExternalSystem(someInput: String): ZIO[Any, Throwable, Int] = ZIO.succeed(someInput.length) val circuitBreaker: ZManaged[Clock, Nothing, CircuitBreaker[Any]] = CircuitBreaker.make( trippingStrategy = TrippingStrategy.failureCount(maxFailures = 10), resetPolicy = Retry.Schedules.exponentialBackoff(min = 1.second, max = 1.minute) ) circuitBreaker.use { cb =&gt; val result: ZIO[Any, CircuitBreakerCallError[Throwable], Int] = cb(callExternalSystem(\"some input\")) result .flatMap(r =&gt; putStrLn(s\"External system returned $r\")) .catchSome { case CircuitBreakerOpen =&gt; putStrLn(\"Circuit breaker blocked the call to our external system\") case WrappedError(e) =&gt; putStrLn(s\"External system threw an exception: $e\") } } } Responding to a subset of errors Often you will want the Circuit Breaker to respond only to certain types of errors from your external system call, while passing through other errors that indicate normal operation. Use the isFailure parameter of CircuitBreaker.make to define which errors are regarded by the Circuit Breaker. sealed trait Error case object ServiceError extends Error case object UserError extends Error val isFailure: PartialFunction[Error, Boolean] = { case UserError =&gt; false case _: Error =&gt; true } def callWithServiceError: ZIO[Any, Error, Unit] = ZIO.fail(ServiceError) def callWithUserError: ZIO[Any, Error, Unit] = ZIO.fail(UserError) CircuitBreaker.make( trippingStrategy = TrippingStrategy.failureCount(maxFailures = 10), isFailure = isFailure ).use { circuitBreaker =&gt; for { _ &lt;- circuitBreaker(callWithUserError) // Will not be counted as failure by the circuit breaker _ &lt;- circuitBreaker(callWithServiceError) // Will be counted as failure } yield () } Monitoring You may want to monitor circuit breaker failures and trigger alerts when the circuit breaker trips. For this purpose, CircuitBreaker publishes state changes via a callback provided to make. Usage: CircuitBreaker.make( trippingStrategy = TrippingStrategy.failureCount(maxFailures = 10), onStateChange = (s: State) =&gt; ZIO(println(s\"State changed to ${s}\")).ignore ).use { circuitBreaker =&gt; // Make calls to an external system circuitBreaker(ZIO.unit) // etc }"
    } ,    
    {
      "title": "Combining Policies",
      "url": "/rezilience/docs/combining_policies/",
      "content": "Combining policies rezilience policies can be composed into one to apply several resilience strategies as one. A composed policy has a wider range of possible errors than an individual policy. This is made explicit by having to convert each policy to an instance of Policy by calling .toPolicy. Such a Policy has a slightly different signature for the apply method in the error type: def apply[R, E1 &lt;: E, A](f: ZIO[R, E1, A]): ZIO[R, PolicyError[E1], A] A policy can be composed with another one using its compose method, which wraps another policy around it. Below is an example of wrapping a Retry around a RateLimiter around a Bulkhead. The for-comprehension is needed because policies are created as ZManageds. val policy: ZManaged[Clock, Nothing, Policy[Any]] = for { rateLimiter &lt;- RateLimiter.make(1, 2.seconds) bulkhead &lt;- Bulkhead.make(2) retry &lt;- Retry.make(Schedule.recurs(3)) } yield bulkhead.toPolicy compose rateLimiter.toPolicy compose retry.toPolicy Composing policies requires some special care in handling policy errors, behavior-wise and type-wise. Take for example a retry around a circuit breaker. Behavior: what is the desired retry behavior when a circuit breaker error is encountered? Should the call be retried or the error passed through to the caller? Types: because a Retry is created with a Schedule that expects a certain type E of errors as input, a Retry[E] cannot be applied on ZIO[R, CircuitBreakerError[E], A] effects. For these cases, the Retry and CircuitBreaker policies have a widen method that can adapt them to a diferent type of error. For example to adapt a Retry[Throwable] to a Retry[PolicyError[Throwable]]: val retry: Retry[Throwable] = ??? val retryComposable = retry.widen[PolicyError[Throwable]] { case Policy.WrappedError(e) =&gt; e } The partial function above is made available as Policy#unwrap[E] for convenience, so that the above can be written as val retryComposable: Retry[PolicyError[Throwable]] = retry.widen(Policy.unwrap[Throwable]) Many variations of policy combinations are possible. The polly project has some good advice for the order in which to compose policies: https://github.com/App-vNext/Polly/wiki/PolicyWrap#usage-recommendations."
    } ,    
    {
      "title": "General Usage",
      "url": "/rezilience/docs/general_usage/",
      "content": "General usage rezilience policies are created as ZManaged resources. This allows them to run background operations which are cleaned up safely after usage. Since these ZManageds are just descriptions of the policy, they can be passed around to various call sites and used to create many instances. All instantiated policies are defined as traits with an apply method that takes a ZIO effect as parameter: trait Retry { def apply[R, E, A](f: ZIO[R, E, A]): ZIO[R, E, A] } Therefore a policy can be used as if it were a function taking a ZIO effect, eg: Retry.make(...).use { retry =&gt; retry(callToExternalSystem) // shorthand for retry.apply(callToExternalSystem) } Policies can be applied to any type of ZIO[R, E, A] effect, although some policies have an upper bound for E depending on how they are created. Some policies alter the return error type, others leave it as is: Policy Error type upper bound Result type CircuitBreaker Any, or E (when isFailure parameter is used) ZIO[R, CircuitBreakerError[E], A] RateLimiter Any ZIO[R, E, A] Bulkhead Any ZIO[R, BulkheadError[E], A] Retry Any, or E when a Schedule[Env, E, Out] is used ZIO[R, E, A] Timeout Any ZIO[R, E, A] Mapping errors rezilience policies are type-safe in the error channel, which means that some of them change the error type of the effects they are applied to (see table above). For example, applying a CircuitBreaker to an effect of type ZIO[Any, Throwable, Unit] will result in a ZIO[Any, CircuitBreakerError[Throwable], Unit]. This CircuitBreakerError has two subtypes: case object CircuitBreakerOpen: the error when the circuit breaker has tripped and no attempt to make the call has been made case class WrappedError[E](error: E): the error coming from the call By having this datatype for errors, rezilience requires you to be explicit in how you want to handle circuit breaker errors, in line with the rest of ZIO’s strategy for typed error handling. At a higher level in your application you may want to inform the user that a system is temporarily not available or execute some fallback logic. Several conveniences are available for dealing with circuit breaker errors: CircuitBreakerError#fold[O](circuitBreakerOpen: O, unwrap: E =&gt; O) Convert a CircuitBreakerOpen or a WrappedError into an O. CircuitBreakerError#toException Converts a CircuitBreakerError to a CircuitBreakerException. For example: sealed trait MyServiceErrorType case object SystemNotInTheMood extends MyServiceErrorType case object UnknownServiceError extends MyServiceErrorType def callExternalSystem(someInput: String): ZIO[Any, MyServiceErrorType, Int] = ZIO.succeed(someInput.length) val result1: ZIO[Any, CircuitBreakerError[MyServiceErrorType], Int] = circuitBreaker(callExternalSystem(\"1234\")) // Map the CircuitBreakerError back onto an UnknownServiceError val result2: ZIO[Any, MyServiceErrorType, Int] = result1.mapError(policyError =&gt; policyError.fold(UnknownServiceError, identity(_))) // Or turn it into an exception val result3: ZIO[Any, Throwable, Int] = result1.mapError(policyError =&gt; policyError.toException) Similar methods exist on BulkheadError and PolicyError (see Bulkhead and Combining Policies) ZLayer integration You can apply rezilience policies at the level of an individual ZIO effect. But having to wrap all your calls in eg a rate limiter can clutter your code somewhat. When you are using the ZIO module pattern using ZLayer, it is also possible to integrate a rezilience policy with some service at the ZLayer level. In the spirit of aspect oriented programming, the code using your service will not be cluttered with the aspect of rate limiting. For example: val addRateLimiterToDatabase: ZLayer[Database with Clock, Nothing, Database] = ZLayer.fromServiceManaged { database: Database.Service =&gt; RateLimiter.make(10).map { rateLimiter =&gt; new Database.Service { override def transfer(amount: Amount, from: Account, to: Account): ZIO[Any, Throwable, Unit] = rateLimiter(database.transfer(amount, from, to)) } } } val env: ZLayer[Clock, Nothing, Database] = (Clock.live ++ databaseLayer) &gt;&gt;&gt; addRateLimiterToDatabase For policies where the result type has a different E you will need to map the error back to your own E. An option is to have something like a general case class UnknownServiceError(e: Exception) in your service error type, to which you can map the policy errors. If that is not possible for some reason, you can also define a new service type like ResilientDatabase where the error types are PolicyError[E]. See the full example for more."
    } ,    
    {
      "title": "Rezilience",
      "url": "/rezilience/",
      "content": ""
    } ,    
    {
      "title": "Rezilience",
      "url": "/rezilience/docs/",
      "content": "Rezilience rezilience is a ZIO-native collection of policies for making asynchronous systems more resilient to failures, inspired by Polly, Resilience4J and Akka. It consists of these policies: Policy Reactive/Proactive Description CircuitBreaker Reactive Temporarily prevent trying calls after too many failures RateLimiter Proactive Limit the rate of calls to a system Bulkhead Proactive Limit the number of in-flight calls to a system Retry Reactive Try again after transient failures Timeout Reactive Interrupt execution if a call does not complete in time Features / Design goals Type-safety: all errors that can result from any of the rezilience policies are encoded in the method signatures, so no unexpected RuntimeExceptions. Support for your own error types (the E in ZIO[R, E, A]) instead of requiring your effects to have Exception as error type Lightweight: rezilience uses only ZIO fibers and will not create threads or blocking Resource-safe: built on ZIO’s ZManaged, any allocated resources are cleaned up safely after use. Call interruptions are handled properly. Thread-safe: all policies are safe under concurrent use. ZIO integration: some policies take for example ZIO Schedules and rezilience tries to help type inference using variance annotations Metrics: all policies (will) provide usage metrics for monitoring purposes Composable: policies can be composed into one overall policy Discoverable: no syntax extensions or implicit conversions, just plain scala Installation Add to your build.sbt: resolvers += Resolver.jcenterRepo libraryDependencies += \"nl.vroste\" %% \"rezilience\" % \"&lt;version&gt;\" The latest version is built against ZIO 1.0.1 and is available for Scala 2.12, 2.13 and Scala.JS 1.2. Usage example Limit the rate of calls: import zio._ import zio.duration._ import nl.vroste.rezilience._ def myCallToExternalResource(someInput: String): ZIO[Any, Throwable, Int] = ??? val rateLimiter: UManaged[RateLimiter] = RateLimiter.make(max = 10, interval = 1.second) rateLimiter.use { rateLimiter =&gt; val result: ZIO[Any, Throwable, Int] = rateLimiter(myCallToExternalResource(\"some input\")) }"
    } ,      
    {
      "title": "Rate Limiter",
      "url": "/rezilience/docs/ratelimiter/",
      "content": "RateLimiter RateLimiter limits the number of calls to some resource to a maximum number in some interval. It is similar to Bulkhead, but while Bulkhead limits the number of concurrent calls, RateLimiter limits the rate of calls. RateLimiter is created without type parameters and allows any effect with any environment and error channel to be called under the protection of rate limiting. Usage import zio._ import zio.duration._ import nl.vroste.rezilience._ // We use Throwable as error type in this example def myCallToExternalResource(someInput: String): ZIO[Any, Throwable, Int] = ??? val rateLimiter: UManaged[RateLimiter] = RateLimiter.make(max = 10, interval = 1.second) rateLimiter.use { rateLimiter =&gt; val result: ZIO[Any, Throwable, Int] = rateLimiter(myCallToExternalResource(\"some input\")) } _NOTE: for typical use cases of resource usage protection, limiting the number of concurrent calls/usage is preferable over limiting the rate of calls. See this excellent talk by Jon Moore on the subject."
    } ,    
    {
      "title": "Retry",
      "url": "/rezilience/docs/retry/",
      "content": "Retry Retry is a policy that retries effects on failure Common retry strategy Retry implements a common-practice strategy for retrying: The first retry is performed immediately. With transient failures this method gives the highest chance of fast success. After that, Retry uses an exponential backoff capped to a maximum duration. Some random jitter is added to prevent spikes of retries from many call sites applying the same retry strategy. An optional maximum number of retries ensures that retrying does not continue forever. Usage example import zio._ import zio.duration._ import zio.clock.Clock import zio.random.Random import nl.vroste.rezilience._ val myEffect: ZIO[Any, Exception, Unit] = ZIO.unit val retry: ZManaged[Clock with Random, Nothing, Retry[Any]] = Retry.make(min = 1.second, max = 10.seconds) retry.use { retryPolicy =&gt; retryPolicy(myEffect) } Custom retry strategy ZIO already has excellent built-in support for retrying effects on failures using a Schedule and rezilience is built on top of that. Retry can accept any ZIO Schedule. Some Schedule building blocks are available in Retry.Schedules: Retry.Schedules.common(min: Duration, max: Duration, factor: Double, retryImmediately: Boolean, maxRetries: Option[Int]) The strategy with immediate retry, exponential backoff and jitter as outlined above. Retry.Schedules.exponentialBackoff(min: Duration, max: Duration, factor: Double = 2.0) Exponential backoff with a maximum delay and an optional maximum number of recurs. When the maximum delay is reached, subsequent delays are the maximum. Retry.Schedules.whenCase[Env, In, Out](pf: PartialFunction[In, Any])(schedule: Schedule[Env, In, Out]) Accepts a partial function and a schedule and will apply the schedule only when the input matches partial function. This is useful to retry only on certain types of failures/exceptions. Different retry strategies for different errors By composing ZIO Schedules, you can define different retries for different types of errors: import java.util.concurrent.TimeoutException import java.net.UnknownHostException val isTimeout: PartialFunction[Exception, Any] = { case _ : TimeoutException =&gt; } val isUnknownHostException: PartialFunction[Exception, Any] = { case _ : UnknownHostException =&gt; } val retry2 = Retry.make( Retry.Schedules.whenCase(isTimeout) { Retry.Schedules.common(min = 1.second, max = 1.minute) } || Retry.Schedules.whenCase(isUnknownHostException) { Retry.Schedules.common(min = 1.day, max = 5.days) } ) retry2.use { retryPolicy =&gt; retryPolicy(myEffect) }"
    } ,      
    {
      "title": "Timeout",
      "url": "/rezilience/docs/timeout/",
      "content": "Timeout Timeout is a policy that interrupts execution of an effect when it does not complete in time. It is a simple wrapper around ZIO#timeout for easy composition with the other policies. Effects of type ZIO[R, E, A] wrapped with a Timeout will get a TimeoutError[E] as error type. This has two subtypes: WrappedError[E] for non-timeout errors and CallTimedOut for timeout errors. Usage example import zio._ import zio.duration._ import zio.clock.Clock import nl.vroste.rezilience._ import nl.vroste.rezilience.Timeout.TimeoutError val myEffect: ZIO[Clock, Exception, Unit] = ZIO.sleep(20.seconds) val timeout: ZManaged[Clock, Nothing, Timeout] = Timeout.make(10.seconds) val result: ZIO[Clock, TimeoutError[Exception], Unit] = timeout.use { policy =&gt; policy(myEffect) } // result will be a ZIO failure with value `CallTimedOut`"
    }    
  ];

  idx = lunr(function () {
    this.ref("title");
    this.field("content");

    docs.forEach(function (doc) {
      this.add(doc);
    }, this);
  });

  docs.forEach(function (doc) {
    docMap.set(doc.title, doc.url);
  });
}

// The onkeypress handler for search functionality
function searchOnKeyDown(e) {
  const keyCode = e.keyCode;
  const parent = e.target.parentElement;
  const isSearchBar = e.target.id === "search-bar";
  const isSearchResult = parent ? parent.id.startsWith("result-") : false;
  const isSearchBarOrResult = isSearchBar || isSearchResult;

  if (keyCode === 40 && isSearchBarOrResult) {
    // On 'down', try to navigate down the search results
    e.preventDefault();
    e.stopPropagation();
    selectDown(e);
  } else if (keyCode === 38 && isSearchBarOrResult) {
    // On 'up', try to navigate up the search results
    e.preventDefault();
    e.stopPropagation();
    selectUp(e);
  } else if (keyCode === 27 && isSearchBarOrResult) {
    // On 'ESC', close the search dropdown
    e.preventDefault();
    e.stopPropagation();
    closeDropdownSearch(e);
  }
}

// Search is only done on key-up so that the search terms are properly propagated
function searchOnKeyUp(e) {
  // Filter out up, down, esc keys
  const keyCode = e.keyCode;
  const cannotBe = [40, 38, 27];
  const isSearchBar = e.target.id === "search-bar";
  const keyIsNotWrong = !cannotBe.includes(keyCode);
  if (isSearchBar && keyIsNotWrong) {
    // Try to run a search
    runSearch(e);
  }
}

// Move the cursor up the search list
function selectUp(e) {
  if (e.target.parentElement.id.startsWith("result-")) {
    const index = parseInt(e.target.parentElement.id.substring(7));
    if (!isNaN(index) && (index > 0)) {
      const nextIndexStr = "result-" + (index - 1);
      const querySel = "li[id$='" + nextIndexStr + "'";
      const nextResult = document.querySelector(querySel);
      if (nextResult) {
        nextResult.firstChild.focus();
      }
    }
  }
}

// Move the cursor down the search list
function selectDown(e) {
  if (e.target.id === "search-bar") {
    const firstResult = document.querySelector("li[id$='result-0']");
    if (firstResult) {
      firstResult.firstChild.focus();
    }
  } else if (e.target.parentElement.id.startsWith("result-")) {
    const index = parseInt(e.target.parentElement.id.substring(7));
    if (!isNaN(index)) {
      const nextIndexStr = "result-" + (index + 1);
      const querySel = "li[id$='" + nextIndexStr + "'";
      const nextResult = document.querySelector(querySel);
      if (nextResult) {
        nextResult.firstChild.focus();
      }
    }
  }
}

// Search for whatever the user has typed so far
function runSearch(e) {
  if (e.target.value === "") {
    // On empty string, remove all search results
    // Otherwise this may show all results as everything is a "match"
    applySearchResults([]);
  } else {
    const tokens = e.target.value.split(" ");
    const moddedTokens = tokens.map(function (token) {
      // "*" + token + "*"
      return token;
    })
    const searchTerm = moddedTokens.join(" ");
    const searchResults = idx.search(searchTerm);
    const mapResults = searchResults.map(function (result) {
      const resultUrl = docMap.get(result.ref);
      return { name: result.ref, url: resultUrl };
    })

    applySearchResults(mapResults);
  }

}

// After a search, modify the search dropdown to contain the search results
function applySearchResults(results) {
  const dropdown = document.querySelector("div[id$='search-dropdown'] > .dropdown-content.show");
  if (dropdown) {
    //Remove each child
    while (dropdown.firstChild) {
      dropdown.removeChild(dropdown.firstChild);
    }

    //Add each result as an element in the list
    results.forEach(function (result, i) {
      const elem = document.createElement("li");
      elem.setAttribute("class", "dropdown-item");
      elem.setAttribute("id", "result-" + i);

      const elemLink = document.createElement("a");
      elemLink.setAttribute("title", result.name);
      elemLink.setAttribute("href", result.url);
      elemLink.setAttribute("class", "dropdown-item-link");

      const elemLinkText = document.createElement("span");
      elemLinkText.setAttribute("class", "dropdown-item-link-text");
      elemLinkText.innerHTML = result.name;

      elemLink.appendChild(elemLinkText);
      elem.appendChild(elemLink);
      dropdown.appendChild(elem);
    });
  }
}

// Close the dropdown if the user clicks (only) outside of it
function closeDropdownSearch(e) {
  // Check if where we're clicking is the search dropdown
  if (e.target.id !== "search-bar") {
    const dropdown = document.querySelector("div[id$='search-dropdown'] > .dropdown-content.show");
    if (dropdown) {
      dropdown.classList.remove("show");
      document.documentElement.removeEventListener("click", closeDropdownSearch);
    }
  }
}
