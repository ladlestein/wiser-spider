package com.nowanswers.wiserspider

import org.specs2.mutable.Specification
import org.specs2.specification.Scope
import concurrent._
import concurrent.duration._
import org.specs2.time.NoTimeConversions
import akka.util.Timeout
import akka.actor.ActorSystem

/**
 * Created with IntelliJ IDEA.
 * User: ladlestein
 * Date: 7/19/13
 * Time: 12:08 AM
 * To change this template use File | Settings | File Templates.
 */
class RealWebInterfaceSpec extends Specification
  with RealWebInterfaceComponent with TestContext with NoTimeConversions {

  "The real web interface" should {

    "fetch a page" in new Scope {
      val duration = (5 seconds)
      Await.result(theWeb.fetchUrl("http://www.w3c.org/", null, false), duration).status.isSuccess must_== true
    }
  }

}

