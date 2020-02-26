/*
 * Zinc - The incremental compiler for Scala.
 * Copyright Lightbend, Inc. and Mark Harrah
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package sbt
package internal
package inc
package classfile

import java.io.{ File, InputStream }
import java.net.URLClassLoader
import javax.tools.{ StandardLocation, ToolProvider }
import java.nio.file.{ Files, Path }

import sbt.io.IO
import sbt.internal.util.ConsoleLogger
import xsbti.api.DependencyContext._
import xsbti.{ AnalysisCallback, TestCallback, VirtualFile, VirtualFileRef }
import xsbti.TestCallback.ExtractedClassDependencies
import xsbti.compile.SingleOutput

import scala.collection.JavaConverters._

object JavaCompilerForUnitTesting {
  private class TestVirtualFile(underlying: Path) extends VirtualFile {
    override def contentHash(): Long = sbt.io.Hash(underlying.toFile).hashCode.toLong
    override def id(): String = underlying.toString
    override def name(): String = underlying.getFileName.toString
    override def names(): Array[String] = id.split("/")
    override def input(): InputStream = Files.newInputStream(underlying)
  }

  def extractDependenciesFromSrcs(srcs: (String, String)*): ExtractedClassDependencies = {
    val (_, testCallback) = compileJavaSrcs(srcs: _*)((_, _, classes) => extractParents(classes))

    val memberRefDeps = testCallback.classDependencies collect {
      case (target, src, DependencyByMemberRef) => (src, target)
    }
    val inheritanceDeps = testCallback.classDependencies collect {
      case (target, src, DependencyByInheritance) => (src, target)
    }
    val localInheritanceDeps = testCallback.classDependencies collect {
      case (target, src, LocalDependencyByInheritance) => (src, target)
    }
    ExtractedClassDependencies.fromPairs(memberRefDeps, inheritanceDeps, localInheritanceDeps)
  }

  def compileJavaSrcs(srcs: (String, String)*)(
      readAPI: (AnalysisCallback, VirtualFileRef, Seq[Class[_]]) => Set[(String, String)]
  ): (Seq[VirtualFile], TestCallback) = {
    IO.withTemporaryDirectory { temp =>
      val srcFiles0 = srcs.map {
        case (fileName, src) => prepareSrcFile(temp, fileName, src)
      }
      val srcFiles: List[VirtualFile] =
        srcFiles0.toList.map(x => new TestVirtualFile(x.toPath): VirtualFile)
      val analysisCallback = new TestCallback
      val classesDir = new File(temp, "classes")
      classesDir.mkdir()

      val compiler = ToolProvider.getSystemJavaCompiler()
      val fileManager = compiler.getStandardFileManager(null, null, null)
      fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Seq(classesDir).asJava)
      val compilationUnits = fileManager.getJavaFileObjectsFromFiles(srcFiles0.asJava)
      compiler.getTask(null, fileManager, null, null, null, compilationUnits).call()
      fileManager.close()

      val classesFinder = sbt.io.PathFinder(classesDir) ** "*.class"
      val classFiles = classesFinder.get.map(_.toPath)

      val classloader = new URLClassLoader(Array(classesDir.toURI.toURL))

      val logger = ConsoleLogger()

      // we pass extractParents as readAPI. In fact, Analyze expect readAPI to do both things:
      // - extract api representation out of Class (and saved it via a side effect)
      // - extract all base classes.
      // we extract just parents as this is enough for testing

      val output = new SingleOutput { def getOutputDirectory: Path = classesDir.toPath }
      JavaAnalyze(classFiles, srcFiles, logger, output, finalJarOutput = None)(
        analysisCallback,
        classloader,
        readAPI(analysisCallback, _, _)
      )
      (srcFiles, analysisCallback)
    }
  }

  private def prepareSrcFile(baseDir: File, fileName: String, src: String): File = {
    val srcFile = new File(baseDir, fileName)
    IO.write(srcFile, src)
    srcFile
  }

  private val extractParents: Seq[Class[_]] => Set[(String, String)] = { classes =>
    def canonicalNames(p: (Class[_], Class[_])): (String, String) =
      p._1.getCanonicalName -> p._2.getCanonicalName
    val parents = classes.map(c => c -> c.getSuperclass)
    val parentInterfaces = classes.flatMap(c => c.getInterfaces.map(i => c -> i))
    (parents ++ parentInterfaces).map(canonicalNames).toSet
  }
}
