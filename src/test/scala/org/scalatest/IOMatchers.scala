package org.scalatest

import cats.effect.IO
import org.scalactic.source

import scala.reflect.ClassTag
import scala.util.Either

trait IOMatchers extends Assertions {

  /**
    * An IO equivalent of assertThrows.
    *
    * Usage:
    * <code>assertIOLeftException[SomeException](someIO.attempt)</code>
    *
    * @param f
    * @param classTag
    * @param pos
    * @tparam T
    * @return
    */
  def assertIOLeftException[T <: AnyRef](f: => IO[Either[_, _]])(implicit classTag: ClassTag[T], pos: source.Position): IO[Assertion] = {
    val clazz = classTag.runtimeClass
    f.map {
      case Left(u) if !clazz.isAssignableFrom(u.getClass) =>
        val message = Resources.expectedButGot(u.getClass, clazz.getCanonicalName)
        throw newAssertionFailedException(Some(message), None, pos, analysis = Vector())
      case Left(_) => Succeeded
      case Right(_) => Succeeded
    }
  }

}
