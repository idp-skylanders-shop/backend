package dataservice

// Schelet Data Service — Etapa 2 (40% gata)
// In etapa urmatoare, acest serviciu va expune un API REST intern
// pe care Backend Service il va apela in loc sa acceseze direct Postgres.
// Astfel, baza de date nu va mai fi accesibila decat prin acest layer.

import io.getquill._

object DataService {

  // TODO: muta conexiunea DB din backend-service aici
  // lazy val ctx = new PostgresJdbcContext(SnakeCase, "ctx")

  // TODO: expune endpoint-uri interne, ex:
  //   GET  /internal/products
  //   POST /internal/products
  //   GET  /internal/cart/:userId
  //   POST /internal/cart/add
  //   DELETE /internal/products/:id

  def main(args: Array[String]): Unit = {
    println("Data Service skeleton — not yet implemented.")
  }
}
