package com.twitter.querulous.async

import java.util.concurrent.{Executors, RejectedExecutionException}
import java.util.concurrent.atomic.{AtomicInteger, AtomicBoolean}
import java.sql.Connection
import com.twitter.util.{Throw, Future, Promise, FuturePool, JavaTimer, TimeoutException}
import com.twitter.querulous.DaemonThreadFactory
import com.twitter.querulous.database.{Database, DatabaseFactory}


class BlockingDatabaseWrapperFactory(
  workPool: => FuturePool,
  checkoutPool: => FuturePool,
  factory: DatabaseFactory)
extends AsyncDatabaseFactory {
  def apply(
    hosts: List[String],
    name: String,
    username: String,
    password: String,
    urlOptions: Map[String, String],
    driverName: String
  ): AsyncDatabase = {
    new BlockingDatabaseWrapper(
      workPool,
      checkoutPool,
      factory(hosts, name, username, password, urlOptions, driverName)
    )
  }
}

private object AsyncConnectionCheckout {
  lazy val checkoutTimer = new JavaTimer(true)
}

class BlockingDatabaseWrapper(
  workPool: FuturePool,
  checkoutPool: FuturePool,
  protected[async] val database: Database)
extends AsyncDatabase {

  import AsyncConnectionCheckout._

  private val openTimeout  = database.openTimeout

  def withConnection[R](f: Connection => R) = {
    checkoutConnection() flatMap { conn =>

      // As a workaround for a FuturePool bug where it may throw away
      // work if cancelled but already in progress, therefore not
      // allowing ensure to predictably clean up, use an AtomicBoolean
      // to allow the finally block and ensure block to race to close
      // the connection.
      val closed = new AtomicBoolean(false)

      workPool {
        try {
          f(conn)
        } finally {
          if (!closed.getAndSet(true)) database.close(conn)
        }
      } ensure {
        if (!closed.getAndSet(true)) database.close(conn)
      }
    }
  }

  private def checkoutConnection(): Future[Connection] = {
    // creating a detached promise here so that cancellations do not
    // propagate to the checkout pool.
    val result = new Promise[Connection]

    checkoutPool { database.open() } respond { result.update(_) }

    // cancel future if it times out
    result.within(checkoutTimer, openTimeout) onFailure { e =>
      if (e.isInstanceOf[java.util.concurrent.TimeoutException]) {
        result foreach { database.close(_) }
      }
    }
  }

  // equality overrides

  override def equals(other: Any) = other match {
    case other: BlockingDatabaseWrapper => database eq other.database
    case _ => false
  }

  override def hashCode = database.hashCode
}
