package my.tests

import org.specs2.mutable._
import org.specs2.specification.Scope
import org.specs2.mock._

trait Stringy {
  def reverse(x: String): String
  def length(x: String): Int
}

class ThisSpec extends Specification with Mockito {

  "This thing" should {

    "stub a method that takes a string and returns a string" in new Scope {

      val f = mock[Stringy]

      f.reverse("bletch") returns "hctelb"
      f.reverse("bletch") must be_==("hctelb")
    }

    "stub a method that takes a string and returns an integer" in new Scope {

      val f = mock[Stringy]

      f.length("bletch") returns 6
      f.length("bletch") should be_==(6)

    }

    "stub a method with an argument matcher" in new Scope {

      val f = mock[Stringy]

      f.reverse(be_===("foo")) returns "oof"
      f.reverse("foo") must be_==("oof")
    }
  }
}

