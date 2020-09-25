package nl.vroste.rezilience.examples

import nl.vroste.rezilience.RateLimiter
import zio._
import zio.clock.Clock

/**
 * Example of how to integrate a rate limiter to an entire Service using ZLayer
 */
object ZLayerIntegrationExample extends zio.App {
  type Account = String
  type Amount  = Int

  // The definition of our Database service
  type Database = Has[Database.Service]

  object Database {
    trait Service {
      def transfer(amount: Amount, from: Account, to: Account): ZIO[Any, Throwable, Unit]
      def newAccount(name: Account): ZIO[Any, Throwable, Unit]
    }

    // Accessors
    def transfer(amount: Amount, from: Account, to: Account): ZIO[Database, Throwable, Unit] =
      ZIO.service[Database.Service].flatMap(_.transfer(amount, from, to))

    def newAccount(name: Account): ZIO[Database, Throwable, Unit] =
      ZIO.service[Database.Service].flatMap(_.newAccount(name))

  }

  // Some live implementation of our Database service
  val databaseLayer: ULayer[Database] = ZLayer.succeed {
    new Database.Service {
      override def transfer(amount: Amount, from: Account, to: Account): ZIO[Any, Throwable, Unit] =
        UIO(println("transfer"))

      override def newAccount(name: Account): ZIO[Any, Throwable, Unit] =
        UIO(println("new account"))
    }
  }

  // A layer that adds a rate limiter to our database
  val addRateLimiterToDatabase: ZLayer[Database with Clock, Nothing, Database] =
    ZLayer.fromServiceManaged { database: Database.Service =>
      RateLimiter.make(10).map { rl =>
        new Database.Service {
          override def transfer(amount: Amount, from: Account, to: Account): ZIO[Any, Throwable, Unit] =
            rl(database.transfer(amount, from, to))

          override def newAccount(name: Account): ZIO[Any, Throwable, Unit] =
            rl(database.newAccount(name))
        }
      }
    }

  // The complete environment of our application
  val env: ZLayer[Clock, Nothing, Database] =
    (Clock.live ++ databaseLayer) >>> addRateLimiterToDatabase

  // Run our program against the Database service being unconcerned with the rate limiter
  override def run(args: List[String]): URIO[ZEnv, ExitCode] =
    (Database.transfer(1, "a", "b") *> Database.transfer(3, "b", "a"))
      .provideCustomLayer(env)
      .exitCode
}
