package ws.backpressuredemo

import org.scalatest.FlatSpecLike
import org.scalatest.Matchers
import org.scalatest.Suite
import org.testng.annotations.Test

class SomeTest extends Suite with FlatSpecLike with Matchers {

  @Test(groups = Array("unit"))
  def testFoo(): Unit = {
  }

}
