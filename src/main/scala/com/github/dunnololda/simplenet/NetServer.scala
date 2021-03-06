package com.github.dunnololda.simplenet

import java.net._
import akka.actor.{Props, ActorSystem, Actor, ActorRef}
import java.io.{InputStreamReader, BufferedReader, OutputStreamWriter, PrintWriter}
import scala.concurrent._
import scala.concurrent.duration._
import akka.pattern.ask
import collection.mutable

// connection listener messages

case object Listen

// connection handler messages

case object Check

case object Ping

case class Send(message: State)

case object Disconnect

// client handler messages

case object RetrieveEvent

case object WaitForEvent

case class SendToClient(client_id: Long, message: State)

case class DisconnectClient(client_id: Long)

sealed abstract class NetworkEvent

case class NewConnection(client_id: Long, client: ActorRef) extends NetworkEvent

case class NewClient(client_id: Long) extends NetworkEvent

case class NewMessage(client_id: Long, message: State) extends NetworkEvent

case class ClientDisconnected(client_id: Long) extends NetworkEvent

case object ServerConnected extends NetworkEvent

case object ServerDisconnected extends NetworkEvent

case class NewServerMessage(data:State) extends NetworkEvent

case object NoNewEvents extends NetworkEvent

object NetServer {
  def apply(port: Int, ping_timeout: Long = 0) = new NetServer(port, ping_timeout)
}

class NetServer(port: Int, ping_timeout: Long = 0) {
  private val log = MySimpleLogger(this.getClass.getName)

  def nextAvailablePort(port: Int): Int = {
    def available(port: Int): Boolean = {
      // TODO: return Option[Int]: None if no available port found within some range
      var ss: ServerSocket = null
      var ds: DatagramSocket = null
      try {
        ss = new ServerSocket(port)
        ss.setReuseAddress(true)
        ds = new DatagramSocket(port)
        ds.setReuseAddress(true)
        return true
      } catch {
        case e: Exception => return false
      } finally {
        if (ds != null) ds.close()
        if (ss != null) ss.close()
      }
      false
    }

    log.info("trying port " + port + "...")
    if (available(port)) {
      log.info("the port is available!")
      port
    } else {
      log.info("the port is busy")
      nextAvailablePort(port + 1)
    }
  }

  private val listen_port = nextAvailablePort(port)

  def listenPort = listen_port

  private val format = new java.text.SimpleDateFormat("yyyyMMddHHmmss")
  private val moment = format.format(new java.util.Date())

  private val connection_listener = ActorSystem("netserver-listener-" + moment)
  // is it necessary to have unique names for actor systems and/or actors? what are the issues if they are not?
  private val handler = connection_listener.actorOf(Props(new ClientHandler))

  connection_listener.actorOf(Props(new Actor {
    override def preStart() {
      log.info("starting actor " + self.path.toString)
      //self ! Listen
      import scala.concurrent.ExecutionContext.Implicits.global
      context.system.scheduler.schedule(initialDelay = (0 seconds), interval = (100 milliseconds)) {
        self ! Listen
      }
    }

    private val server_socket = new ServerSocket(listen_port)

    private var client_id: Long = 0

    private def nextClientId: Long = {
      client_id += 1
      client_id
    }

    def receive = {
      case Listen =>
        val socket = server_socket.accept()
        val new_client_id = nextClientId
        val new_client = context.actorOf(Props(new ConnectionHandler(new_client_id, socket, ping_timeout, handler)))
        handler ! NewConnection(new_client_id, new_client)
        self ! Listen
    }
  }))

  def newEvent: NetworkEvent = {
    Await.result(handler.ask(RetrieveEvent)(timeout = (1 minute)), 1 minute).asInstanceOf[NetworkEvent]
  }

  def waitNewEvent: NetworkEvent = {
    Await.result(handler.ask(WaitForEvent)(timeout = (1000 days)), 1000 days).asInstanceOf[NetworkEvent]
  }

  def sendToClient(client_id: Long, message: State) {
    handler ! SendToClient(client_id, message)
  }

  def sendToAll(message: State) {
    handler ! Send(message)
  }

  def disconnectClient(client_id:Long) {
    handler ! DisconnectClient(client_id)
  }

  def disconnectAll() {
    handler ! Disconnect
  }

  def stop() {
    connection_listener.shutdown()
  }
}

class ConnectionHandler(id: Long, socket: Socket, ping_timeout: Long = 0, handler: ActorRef) extends Actor {
  private val log = MySimpleLogger(this.getClass.getName)

  private val out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream, "UTF-8"))
  private val in = new BufferedReader(new InputStreamReader(socket.getInputStream, "UTF-8"))

  override def preStart() {
    log.info("starting actor " + self.path.toString)
    import scala.concurrent.ExecutionContext.Implicits.global
    context.system.scheduler.schedule(initialDelay = (0 seconds), interval = (100 milliseconds)) {
      self ! Check
    }
    if (ping_timeout > 0) {
      context.system.scheduler.schedule(initialDelay = (ping_timeout milliseconds), interval = (ping_timeout milliseconds)) {
        self ! Ping
      }
    }
  }

  override def postStop() {
    socket.close()
    handler ! ClientDisconnected(id)
  }

  private var last_interaction_moment = 0l

  def receive = {
    case Check =>
      if (in.ready) {
        try {
          val message = in.readLine
          val received_data = State.fromJsonStringOrDefault(message, State(("raw" -> message)))
          if (!received_data.contains("ping")) {
            handler ! NewMessage(id, received_data)
          }
          last_interaction_moment = System.currentTimeMillis()
        } catch {
          case e: Exception =>
        }
      }
    case Send(message) =>
      out.println(message.toJsonString)
      out.flush()
      if (out.checkError()) {
        self ! Disconnect
      } else {
        last_interaction_moment = System.currentTimeMillis()
      }
    case Ping =>
      if (System.currentTimeMillis() - last_interaction_moment > ping_timeout) {
        out.println(State("ping").toJsonString)
        out.flush()
        if (out.checkError()) {
          self ! Disconnect
        }
      }
    case Disconnect =>
      context.stop(self)
  }
}

class ClientHandler extends Actor {
  private val network_events = collection.mutable.ArrayBuffer[NetworkEvent]()
  private val clients = mutable.HashMap[Long, ActorRef]()
  private var event_waiter:Option[ActorRef] = None

  def receive = {
    case event @ NewConnection(client_id, client_actor) =>
      clients += (client_id -> client_actor)
      if (event_waiter.nonEmpty) {
        event_waiter.get ! NewClient(client_id)
        event_waiter = None
      }
      else network_events += NewClient(client_id)
    case event @ ClientDisconnected(client_id) =>
      clients -= client_id
      if (event_waiter.nonEmpty) {
        event_waiter.get ! event
        event_waiter = None
      } else network_events += event
    case event: NetworkEvent =>
      if (event_waiter.nonEmpty) {
        event_waiter.get ! event
        event_waiter = None
      } else network_events += event
    case RetrieveEvent =>
      if (network_events.isEmpty) sender ! NoNewEvents
      else sender ! network_events.remove(0)
    case WaitForEvent =>
      if (network_events.nonEmpty) sender ! network_events.remove(0)
      else event_waiter = Some(sender)
    case SendToClient(client_id, message) =>
      clients.get(client_id).foreach(client => client ! Send(message))
    case Send(message) =>
      clients.values.foreach(client => client ! Send(message))
    case DisconnectClient(client_id) =>
      clients.get(client_id).foreach(client => client ! Disconnect)
    case Disconnect =>
      clients.values.foreach(client => client ! Disconnect)
  }
}
