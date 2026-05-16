package dataservice

import io.getquill._
import upickle.default.{ReadWriter => RW, macroRW, write}
import java.util.concurrent.atomic.AtomicLong
import scala.collection.concurrent.TrieMap

// -------------------------------------------------------
// Domain models (same schema as backend-service used)
// -------------------------------------------------------

case class Skylander(
  id: Int,
  name: String,
  element: String,
  series: String,
  price: Double,
  stock: Int,
  image_url: String,
  description: String,
  owner: String
)
object Skylander { implicit val rw: RW[Skylander] = macroRW }

case class CartItem(id: Int, userId: String, productId: Int)
object CartItem { implicit val rw: RW[CartItem] = macroRW }

// -------------------------------------------------------
// Simple Prometheus-compatible counter registry
// -------------------------------------------------------

object Metrics {
  private val counts = TrieMap[String, AtomicLong]()

  def inc(endpoint: String): Unit =
    counts.getOrElseUpdate(endpoint, new AtomicLong(0)).incrementAndGet()

  def toText: String = {
    val sb = new StringBuilder
    sb.append("# HELP ds_requests_total Total requests processed by Data Service\n")
    sb.append("# TYPE ds_requests_total counter\n")
    counts.foreach { case (ep, c) =>
      val safe = ep.replace("\"", "\\\"")
      sb.append(s"""ds_requests_total{endpoint="$safe"} ${c.get}\n""")
    }
    sb.toString()
  }
}

// -------------------------------------------------------
// Data Service — the ONLY component that touches PostgreSQL
// Listens on port 8082, internal network only (not exposed via Kong)
// -------------------------------------------------------

object DataService extends cask.MainRoutes {
  override def host: String = "0.0.0.0"
  override def port: Int    = 8082

  lazy val ctx = new PostgresJdbcContext(SnakeCase, "ctx")
  import ctx._

  inline def skylanders = quote { querySchema[Skylander]("skylanders") }
  inline def cartItems  = quote { querySchema[CartItem]("cart_items") }

  private val cors = Seq("Access-Control-Allow-Origin" -> "*")

  // ---- Health & Metrics ---------------------------------------------------

  @cask.get("/health")
  def health() = cask.Response("""{"status":"ok"}""", headers = cors)

  @cask.get("/metrics")
  def metrics() = cask.Response(
    Metrics.toText,
    headers = Seq("Content-Type" -> "text/plain; version=0.0.4; charset=utf-8")
  )

  // ---- Products -----------------------------------------------------------

  @cask.get("/internal/products")
  def listProducts() = {
    Metrics.inc("GET /internal/products")
    try {
      cask.Response(write(ctx.run(skylanders)), headers = cors)
    } catch {
      case e: Exception =>
        cask.Response(ujson.Obj("error" -> e.getMessage).render(), statusCode = 500, headers = cors)
    }
  }

  @cask.post("/internal/products")
  def createProduct(request: cask.Request) = {
    Metrics.inc("POST /internal/products")
    try {
      val json = ujson.read(request.text())
      val s = Skylander(
        id          = 0,
        name        = json("name").str,
        element     = json("element").str,
        series      = json("series").str,
        price       = json("price").num,
        stock       = json("stock").num.toInt,
        image_url   = json("image_url").str,
        description = json("description").str,
        owner       = json("owner").str
      )
      val newId = ctx.run(skylanders.insertValue(lift(s)).returningGenerated(_.id))
      cask.Response(ujson.Obj("id" -> newId, "success" -> true).render(), headers = cors)
    } catch {
      case e: Exception =>
        e.printStackTrace()
        cask.Response(ujson.Obj("error" -> e.getMessage).render(), statusCode = 500, headers = cors)
    }
  }

  @cask.get("/internal/products/:id")
  def getProduct(id: Int) = {
    Metrics.inc("GET /internal/products/:id")
    ctx.run(skylanders.filter(_.id == lift(id))).headOption match {
      case Some(p) => cask.Response(write(p), headers = cors)
      case None    => cask.Response(ujson.Obj("error" -> "Not found").render(), statusCode = 404, headers = cors)
    }
  }

  @cask.delete("/internal/products/:id")
  def deleteProduct(id: Int) = {
    Metrics.inc("DELETE /internal/products/:id")
    try {
      ctx.run(skylanders.filter(_.id == lift(id)).delete)
      cask.Response(ujson.Obj("success" -> true).render(), headers = cors)
    } catch {
      case e: Exception =>
        cask.Response(ujson.Obj("error" -> e.getMessage).render(), statusCode = 500, headers = cors)
    }
  }

  // ---- Cart ---------------------------------------------------------------

  @cask.get("/internal/cart/items/:userId")
  def getCartItems(userId: String) = {
    Metrics.inc("GET /internal/cart/items/:userId")
    try {
      val items = ctx.run(quote {
        for {
          c <- cartItems.filter(c => c.userId == lift(userId))
          p <- skylanders if p.id == c.productId
        } yield (c, p)
      })
      val json = items.map { case (c, p) =>
        ujson.Obj(
          "cartId"    -> c.id,
          "productId" -> p.id,
          "name"      -> p.name,
          "price"     -> p.price,
          "stock"     -> p.stock
        )
      }
      cask.Response(ujson.Arr.from(json).render(), headers = cors)
    } catch {
      case e: Exception =>
        cask.Response(ujson.Obj("error" -> e.getMessage).render(), statusCode = 500, headers = cors)
    }
  }

  // Uses SELECT ... FOR UPDATE to prevent concurrent overselling
  @cask.post("/internal/cart/add")
  def addToCart(request: cask.Request) = {
    Metrics.inc("POST /internal/cart/add")
    try {
      val json      = ujson.read(request.text())
      val productId = json("productId").num.toInt
      val userId    = json("userId").str

      ctx.transaction {
        val maybeProduct = ctx.run(
          sql"SELECT * FROM skylanders WHERE id = ${lift(productId)} FOR UPDATE"
            .as[Query[Skylander]]
        ).headOption

        maybeProduct match {
          case Some(p) if p.stock > 0 =>
            ctx.run(quote {
              skylanders.filter(_.id == lift(productId))
                        .update(p => p.stock -> (p.stock - 1))
            })
            ctx.run(quote {
              cartItems.insert(_.userId -> lift(userId), _.productId -> lift(productId))
            })
            cask.Response(ujson.Obj("status" -> "success").render(), headers = cors)

          case Some(_) =>
            cask.Response(
              ujson.Obj("status" -> "error", "message" -> "Out of stock").render(),
              statusCode = 400, headers = cors
            )

          case None =>
            cask.Response(
              ujson.Obj("status" -> "error", "message" -> "Product not found").render(),
              statusCode = 404, headers = cors
            )
        }
      }
    } catch {
      case e: Exception =>
        e.printStackTrace()
        cask.Response(
          ujson.Obj("status" -> "error", "message" -> e.getMessage).render(),
          statusCode = 500, headers = cors
        )
    }
  }

  @cask.post("/internal/cart/remove")
  def removeFromCart(request: cask.Request) = {
    Metrics.inc("POST /internal/cart/remove")
    try {
      val json      = ujson.read(request.text())
      val cartId    = json("cartId").num.toInt
      val productId = json("productId").num.toInt

      ctx.transaction {
        ctx.run(quote { cartItems.filter(_.id == lift(cartId)).delete })
        ctx.run(quote {
          skylanders.filter(_.id == lift(productId))
                    .update(p => p.stock -> (p.stock + 1))
        })
      }
      cask.Response(ujson.Obj("status" -> "success").render(), headers = cors)
    } catch {
      case e: Exception =>
        e.printStackTrace()
        cask.Response(
          ujson.Obj("status" -> "error", "message" -> e.getMessage).render(),
          statusCode = 500, headers = cors
        )
    }
  }

  @cask.post("/internal/cart/clear/:username")
  def clearCart(username: String) = {
    Metrics.inc("POST /internal/cart/clear/:username")
    try {
      ctx.transaction {
        val userItems = ctx.run(quote { cartItems.filter(_.userId == lift(username)) })
        for (item <- userItems)
          ctx.run(quote {
            skylanders.filter(_.id == lift(item.productId))
                      .update(p => p.stock -> (p.stock + 1))
          })
        ctx.run(quote { cartItems.filter(_.userId == lift(username)).delete })
      }
      cask.Response(ujson.Obj("status" -> "success", "message" -> "Cart cleared").render(), headers = cors)
    } catch {
      case e: Exception =>
        cask.Response(
          ujson.Obj("status" -> "error", "message" -> e.getMessage).render(),
          statusCode = 500, headers = cors
        )
    }
  }

  // Checkout: stock was already decremented on addToCart; just purge cart rows
  @cask.post("/internal/cart/checkout/:username")
  def checkout(username: String) = {
    Metrics.inc("POST /internal/cart/checkout/:username")
    try {
      ctx.transaction {
        ctx.run(quote { cartItems.filter(_.userId == lift(username)).delete })
      }
      cask.Response(ujson.Obj("status" -> "success", "message" -> "Order placed").render(), headers = cors)
    } catch {
      case e: Exception =>
        cask.Response(
          ujson.Obj("status" -> "error", "message" -> e.getMessage).render(),
          statusCode = 500, headers = cors
        )
    }
  }

  initialize()
}
