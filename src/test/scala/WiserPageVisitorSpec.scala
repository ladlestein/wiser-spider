import com.nowanswers.wiserspider.WiserSpiderApp.{QueryIssue, WiserPageVisitorComponent}
import org.specs2.mutable._
import org.specs2.specification.Scope
import org.specs2.mock._
import concurrent._
import spray.http.{HttpBody, HttpResponse}
import akka.pattern._
import akka.util.Timeout



/**
 * Created with IntelliJ IDEA.
 * User: ladlestein
 * Date: 7/9/13
 * Time: 5:05 PM
 * To change this template use File | Settings | File Templates.
 */
class WiserPageVisitorSpec
  extends Specification with WiserPageVisitorComponent with Mockito {

  import ExecutionContext.Implicits.global

  implicit val timeout = Timeout(60 * 60 * 2) // I want to use Timeout(2 hours) but it's not compiling!

  val theWeb = mock[WebInterface]
  val processor = mock[ResultsProcessor]

  "The wiser page visitor" should {
    "pass results along" in new Scope {

      val url = "http://something"
      val body = "stuff"
      val issueName = "things"
      theWeb.fetchUrl(url, null, false) returns future { HttpResponse(entity = HttpBody(body)) }
      visitor ? QueryIssue(url, issueName)
      there was one(processor.processResults(body, issueName))
    }
  }
}