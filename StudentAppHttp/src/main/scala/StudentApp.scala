import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.Done
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import spray.json.RootJsonFormat
import scala.concurrent.ExecutionContextExecutor
// for JSON serialization/deserialization following dependency is required:
// "com.typesafe.akka" %% "akka-http-spray-json" % "10.1.7"
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import scala.concurrent.Future
import java.io.{FileInputStream, FileOutputStream, FileNotFoundException, IOException}
import scala.io.StdIn

final case class Student(name: String, age: Int, mark: Double) extends Serializable
final case class StudentJournal(records: List[Student]) extends Serializable

object StudentApp {

  implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "StudentApp")
  implicit val executionContext: ExecutionContextExecutor = system.executionContext

  implicit val studentFormat: RootJsonFormat[Student] = jsonFormat3(Student)
  implicit val journalFormat: RootJsonFormat[StudentJournal] = jsonFormat1(StudentJournal)

  // (fake) async database query api
  def getStudent(studentName: String): Future[Option[Student]] = Future {
    val students = read()
    students.find(s => s.name == studentName)
  }

  def getStudents: List[Student] = {
    read()
  }

  def deleteStudent(studentName: String): Future[Option[Student]] = Future {
    val students = read()
    val newList = students.filterNot(s => s.name == studentName)
    write(newList)
    students.find(s => s.name == studentName)
  }

  def addStudent(journal: StudentJournal): Future[Done] = {
    val students = read()
    val newList = journal.records ::: students
    write(newList)
    Future { Done }
  }


  def updateStudent(journal: StudentJournal): Future[Done] = {
        val students = read()
        journal.records.headOption match {
          case Some(record) =>
            val oldStudents = students.filterNot(s => s.name == record.name)
            val newStudents = journal.records ::: oldStudents
            write(newStudents)
            Future{ Done }
          case None => Future { Done }
        }
      }


  def using[A <: { def close(): Unit }, B](resource: A)(f: A => B): B =
    try {
      f(resource)
    } finally {
      resource.close()
    }

  def write(students: List[Student]): Unit = {
    try {
      val outputStream = new FileOutputStream("students.txt")
      using(outputStream) { source =>
        source.write(journalFormat.write(StudentJournal(students)).toString.getBytes)
      }
    } catch {
      case e: FileNotFoundException => println("Couldn't find that file.")
      case e: IOException => println("Got an IOException!")
    }
  }

  def read(): List[Student] = {
    try {
      val inputStream = new FileInputStream("students.txt")
      using(inputStream) { source =>
        val json = scala.io.Source.fromInputStream(source).mkString

        import spray.json._
        val students = json.parseJson.convertTo[StudentJournal]

        println(s"Was read object. Object : $students")
        students.records
      }
    } catch {
      case e: FileNotFoundException =>
        println("Couldn't find that file.")
        List.empty
      case e: Exception =>
        e.printStackTrace()
        List.empty
    }
  }

  def main(args: Array[String]): Unit = {
    val route: Route =
      concat(
        get {
          pathPrefix("student" / Remaining) { name =>
            val maybeItem: Future[Option[Student]] = getStudent(name)
            onSuccess(maybeItem) {
              case Some(student) => complete(student)
              case None => complete("No such student to get\n")
            }
          }
        },
        delete {
          pathPrefix("student" / Remaining) { name =>
            val maybeItem: Future[Option[Student]] = deleteStudent(name)
            onSuccess(maybeItem) {
              case Some(student) => complete(student)
              case None => complete("No such student to delete\n")
            }
          }
        },
        put {
          path("student") {
            entity(as[StudentJournal]) { student =>
              val saved: Future[Done] = updateStudent(student)
              onSuccess(saved) { _ => complete("Student updated\n")
              }
            }
          }
        },
        post {
          path("student") {
            entity(as[StudentJournal]) { student =>
              val saved: Future[Done] = addStudent(student)
              onSuccess(saved) { _ => complete("Student added\n")
              }
            }
          }
        },
        get {
          path("student") {
            complete(getStudents)
          }
        }
      )

    val bindingFuture = Http().newServerAt("localhost", 8080).bind(route)
    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
    StdIn.readLine() // let it run until user presses return
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ => system.terminate()) // and shutdown when done
  }
}
