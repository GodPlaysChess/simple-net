package com.github.dunnololda.simplenet.tests

import com.github.dunnololda.simplenet._

object EchoServer extends App {
  val server = NetServer(port = 9000)

  while(true) {
    server.waitNewEvent match {
      case NewMessage(client_id, message) =>
        server.sendToAll(message)
      case _ =>
    }
  }
}

object ArithmeticServer extends App {
  val server = NetServer(port = 9000)

  while(true) {
    server.waitNewEvent match {
      case NewMessage(client_id, State(("a", a:Float), ("b", b:Float), ("op", op:String))) =>
        op match {
          case "+" => server.sendToClient(client_id, State("result" -> (a + b)))
          case "-" => server.sendToClient(client_id, State("result" -> (a - b)))
          case "*" => server.sendToClient(client_id, State("result" -> (a * b)))
          case "/" => server.sendToClient(client_id, State("result" -> (a / b)))  // no division by zero checking to keep example simple
          case _   => server.sendToClient(client_id, State("result" -> ("unknown op: " + op)))
        }
      case _ =>
    }
  }
}

object ArithmeticClient extends App {
  val client = NetClient("localhost", 9000, 0)

  while(true) {
    val (a, b) = ((math.random*100).toFloat, (math.random*100).toFloat)
    val (op, answer) = (math.random*4).toInt match {
      case 0 => ("+", a+b)
      case 1 => ("-", a-b)
      case 2 => ("*", a*b)
      case 3 => if (b != 0) ("/", a/b) else ("+", a+b)
      case _ => ("+", a+b)
    }
    client.send(State("a" -> a, "b" -> b, "op" -> op))
    client.waitNewEvent match {
      case NewServerMessage(State(("result", server_answer:Float))) =>
        println("answer: "+answer+"; server answer: "+server_answer)
      case _ =>
    }
    Thread.sleep(5000)
  }
}
