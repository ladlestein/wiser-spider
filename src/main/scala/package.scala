package com.nowanswers

import akka.actor.ActorSystem
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext


package object wiserspider {

  val API_KEY = "5afb532c23893c3a932a38cce6d7b06f"
  val FETCH_SIZE = 1000
  val SECRET = "ec8efd4d64c84ab64c099642808393b8"
  val N_API_CALLERS = 1
  val TARGET_HOSTNAME = "www.wiser.org"

  val system = ActorSystem("spider")
  val mlog = system.log

  val dispatcher = system.dispatcher

//  implicit val ec = ExecutionContext.fromExecutor(dispatcher)


  trait ProductionContext extends RunContext {
    def system = wiserspider.system
    def mlog = wiserspider.mlog
  }

  implicit val timeout = Timeout(2 hours)

}