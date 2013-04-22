/*
 * Copyright (c) 2013 Habla Computing
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.hablapps.updatable

import language.implicitConversions
import scala.reflect.api.Universe
import scala.reflect.macros.Context

/** Does conform the `updatable` metamodel.
  *
  * Keeps metainformation about updatable models, which mainly consist on types
  * and attributes. Types are declared by introducing new traits. Attributes are
  * just the values declared inside the types. The metamodel could be used for
  * two major reasons. On the one hand, it can be loaded in compilation time to
  * generate some code. On the other hand, it could be instantiated in runtime
  * to bring reflection to the programmer.
  *
  * The `updatable` metamodel relies on the scala model. We treat traits as
  * `updatable` types and values as `updatable` attributes. For this reason, we
  * thought the best alternative would be to generate the `updatable` metamodel
  * by extending the scala reflection API. Hence, we have associated the
  * updatable types with the scala types and the attributes with the scala
  * symbols. This could be a less optimal solution, but it makes the
  * understandability easier.
  */
trait MetaModelAPI {
  type Tpe <: TypeAPI
  type AttTpe <: AttributeTypeAPI
  type Att <: AttributeAPI

  /** A generic universe for scala reflection. */
  val universe: Universe

  /** Does contain metainformation about an `updatable` type.
    *
    * Extends an scala `Type` by adapting it to the updatable one. This will
    * require filtering attributes by their kind.
    *
    * @param tpe the scala type to extends its functionality
    */
  abstract class TypeAPI(val tpe: universe.Type) {

    /** Returns the name of this type. */
    def name: String = tpe.typeSymbol.name.toString

    /** Returns the base class (if any).
      *
      * This method returns the main base class. Notice that we do not consider
      * `Object` or `Any` as main classes, so the number of base classes must
      * be greater than 3 (self, Object, Any) to return something.
      */
    def base: Option[Tpe] =
      if (tpe.baseClasses.size > 3)
	Option(tpe.baseClasses(1).asType.toType)
      else
	None

    /** Returns all the attributes, whether abstract or not. */
    def all: List[Att] = members filter { sym =>
      sym.isTerm && sym.asTerm.isAccessor && sym.isDeferred(tpe)
    } map { toAtt(_) }

    /** Returns only the attributes declared by this type. */
    def declared: List[Att] = declarations filter { sym =>
      sym.isTerm && sym.asTerm.isAccessor && sym.isDeferred(tpe)
    } map { toAtt(_) }

    /** Returns only the attributes inherited from the parent type. */
    def inherited: List[Att] = all filter { s => ! (declared contains s) }

    /** Returns only the abstract attributes. */
    def abxtract: List[Att] = all filter { _.isAbstract(tpe.widen) }

    /** Returns only the concreted attributes. */
    def concreted: List[Att] = all filter { _.isConcrete(tpe.widen) }

    def fynal: List[Att] = all.filter { _.isFynal(tpe.widen) }

    /** Returns the abstract type members. */
    def abstractTpes: List[universe.Symbol] = members filter { s => 
      s.isType && s.asType.isAbstractType
    }

    /** Returns true if this type is abstract.
      *
      * A type is abstract if any of its members is abstract, whether attributes
      * or inner '''types'''.
      * {{{
      * // abstract type!
      * trait A { type T }
      * }}}
      */
    def isAbstract: Boolean = 
      (all find { _.isAbstract(tpe.widen) }).isDefined || hasAbstractTpes

    /** Returns true if this type is concrete.
      *
      * A type is concrete if all its members are concrete, I mean, if all inner
      * types are concreted and all the attributes types are concretes as well.
      */
    def isConcrete: Boolean = ! isAbstract

    /** Returns true if the type has not got attributes, so it is empty. */
    def isEmpty: Boolean = isConcrete && concreted.isEmpty && undeferred.isEmpty

    private def declarations = tpe.declarations.toList.reverse

    private def members = tpe.members.toList.reverse

    private def undeferred = members filter { sym =>
      sym.isTerm && sym.asTerm.isAccessor && sym.isUndeferred(tpe)
    }

    def hasAbstractTpes: Boolean = (members find { s => 
      s.isType && s.asType.isAbstractType
    }).isDefined
  }

  /** Does contain metainformation about an attribute type.
    *
    * First, it is important to mention that '''this is not the metainformation
    * of an attribute'''. Instead, this is the metainformation of an attribute's
    * type. Refer to [[AttributeAPI]] if you are interested in the attribute
    * reifications.
    *
    * This metainformation is interesting to split an attribute type into
    * container and element. Besides, knowing if an attribute is abstract or not
    * becomes easier.
    *
    * @todo type erasure does not allow us to inspect the kind of the type.
    * The only solution is to force an asInstanceOf to get the nullary method
    * type. However, while dealing with abstract types, the asSeenFrom usage
    * returns an existential type that wraps the NMT, and the cast fails.
    * 1. Why is pattern matching limited here?
    * 2. Are there any other type cases besides NMTs and ETs?
    * @param _tpe the raw accessor type 
    */
  abstract class AttributeTypeAPI(_tpe: universe.Type) {

    val tpe = try { 
      _tpe.asInstanceOf[universe.NullaryMethodType].resultType
    } catch {
      case _: Throwable => { 
  	_tpe.asInstanceOf[universe.ExistentialType].underlying.
	  asInstanceOf[universe.NullaryMethodType].resultType
      }
    }

    /** Returns the container (type constructor) of this type.
      *
      * Notice this method wraps the result in an `Option`. This is done
      * this way because a simple type could not have a container.
      * {{{
      * typeOf[Option[Int]].c  // Some(Option[_])
      * typeOf[List[Double]].c // Some(List[_])
      * typeOf[String].c       // None => no container was found
      * }}}
      */
    def c: Option[universe.Type] =
      if (tpe.typeConstructor.takesTypeArgs)
        Some(tpe.typeConstructor.widen)
      else
        None

    /** Returns the element (type argument) of this type.
      *
      * If no container is found, this type is returned verbatim.
      */
    def e: universe.Type =
      if (tpe.typeConstructor.takesTypeArgs)
        tpe.asInstanceOf[universe.TypeRef].args(0).widen
      else
        tpe.widen

    /** Returns true if this attribute type is locally abstract.
      *
      * Here it worths explaining what does ''locally'' mean. There are some
      * situations where an entity can use external abstract types. For example,
      * in the next snippet:
      *
      * {{{
      * trait Model {
      *   type *[_]
      *
      *   trait A {
      *     type A2
      *
      *     val a1: *[Int] // is abstract, but not locally
      *     val a2: A2     // is locally abstract!
      *   }
      * }
      * }}}
      * 
      * `a1` is typed with the external type `*` which is abstract and
      * external. On the other hand, `a2` is typed with the local `A2`,
      * which is abstract and local, regarding `A`. This metamodel only
      * takes locally abstract attributes into account to check if a type is
      * abstract.
      * 
      * @todo this is not working with type constructor e's
      */
    def isAbstract(asf: universe.Type): Boolean = {
      
      def isLocalAbstract(t: universe.Type): Boolean =
	t.widen.typeSymbol.asType.isAbstractType &&
	  asf.members.toList.contains(t.typeSymbol)

      def isAbstractType(t: universe.Type): Boolean =
	if (t.typeConstructor.takesTypeArgs)
	  isLocalAbstract(t.typeConstructor) || 
	    isAbstractList(t.asInstanceOf[universe.TypeRef].args)
	else
	  isLocalAbstract(t)

      def isAbstractList(args: List[universe.Type]): Boolean =
	(args find { isAbstractType(_) }).isDefined

      isAbstractType(tpe)
    }

    /** Returns true if both container and element are concrete. */
    def isConcrete(asf: universe.Type): Boolean = ! isAbstract(asf)

    def isFynal(asf: universe.Type): Boolean = {

      def isFynalType(t: universe.Type): Boolean =
	if (t.typeConstructor.takesTypeArgs)
	  t.widen.typeSymbol.isFinal &&
	    isFynalList(t.asInstanceOf[universe.TypeRef].args)
	else
	  t.widen.typeSymbol.isFinal

      def isFynalList(args: List[universe.Type]): Boolean =
	(args find { ! isFynalType(_) }).isEmpty

      isFynalType(tpe)
    }

    def isId: Boolean =
      // not really beautiful, but Id[_] is a tricky type to play with
      (! c.isDefined) ||
	tpe.typeConstructor.toString == "org.hablapps.updatable.Id"

    def isModifiable: Boolean = ! isId

    override def toString = {
      import scala.util.matching.Regex

      type Cleaner = String => String

      val cleanScala: Cleaner =
	new Regex("scala\\.").replaceAllIn(_, "")

      val cleanEnclosing: Cleaner =
	new Regex("\\w+\\.this\\.").replaceAllIn(_, "")

      def clean(s: String): String =
	(cleanScala compose cleanEnclosing)(s)

      def typeToString(t: universe.Type) =
	clean(t.toString)

      def listToString(l: List[universe.Type]) =
	l map (typeToString(_)) mkString ","

      if (isId && (! tpe.typeConstructor.takesTypeArgs))
	"Id[" + typeToString(tpe) + "]"
      else if (isId)
	"Id[" + listToString(tpe.asInstanceOf[universe.TypeRef].args) + "]"
      else
	typeToString(tpe)
    }
  }

  /** Does contain metainformation about attributes.
    *
    * Extends a symbol to fulfill the `updatable` attribute needs. This is also
    * known as attribute reification along the project. Most of the methods
    * declared on `Type` rely on the methods exposed here.
    *
    * @param sym the symbol representing the trait member
    * @see [[org.hablapps.updatable.WeakBuilder]]
    * @see [[org.hablapps.updatable.Builder]]
    */
  abstract class AttributeAPI(val sym: universe.Symbol) {

    /** Returns the attribute name. */
    def name: String = sym.name.toString

    /** Returns the type of this attribute, as seen from `asf`.
      *
      * Creates an instance of the type associated to this attribute. Since
      * symbols are independent, as well as `updatable` builders are, it is
      * mandatory to specify who is the type requesting the information.
      * This will be clearer in the next example, where attribute `a1` offers
      * different point of views, depending on the requester.
      * 
      * {{{
      * trait A { type T; val a1: T }
      * trait B extends A { type T = Int }
      * ...
      * a1Sym.tpe(asf = typeOf[A]).isAbstract // true, a1 belongs to T
      * a1Sym.tpe(asf = typeOf[B]).isAbstract // false, a1 is a known Int
      * }}}
      */
    def tpe(asf: universe.Type): AttTpe  =
      toAttTpe(sym.typeSignature.asSeenFrom(asf, sym.owner))

    /** Returns the owner of this type. */
    def owner: Tpe  = sym.owner.asType.toType

    /** Returns the default value for this attribute.
      *
      * This method looks for the `@default` annotation in the attribute
      * type and if found, the `value` is returned. For instance, in this
      * snippet:
      * 
      * {{{
      * trait A {
      *   val a1: Int @default(33)
      *   val a2: String
      * }
      * }}}
      *
      * this method returns Some(33) for `a1` and None for `a2`.
      */
    def default: Option[_]

    /** Returns true if this is an abstract attribute, as seen from `asf`.
     *
     * @param asf as seen from
     */
    def isAbstract(asf: universe.Type) = tpe(asf).isAbstract(asf)

    /** Returns true if this is a concrete attribute, as seen from `asf`.
      *
      * @param asf as seen from
      */
    def isConcrete(asf: universe.Type) = tpe(asf).isConcrete(asf)

    def isFynal(asf: universe.Type) = tpe(asf).isFynal(asf)

    /** Returns true if this attribute is declared by `asf`.
      *
      * @param asf as seen from
      */ 
    def isDeclared(asf: universe.Type)  = asf.declared contains this

    /** Returns true if this attribute is inherited, as seen from `asf`.
      *
      * @param asf as seen from
      */
    def isInherited(asf: universe.Type) = ! isDeclared(asf)

    /** Returns true if this attribute has not got a value set, as seen from
     * `asf`.
     *
     * @param asf as seen from
     */
    def isDeferred(asf: universe.Type) = (owner.tpe.members.toList find { s =>
      ((s.name.decoded == (sym.name.decoded + " ")) && !s.isMethod)
    }).isEmpty

    /** Returns true if this attribute has been given a value, as seen from
      * `asf`. 
      *
      * @param asf as seen from
      */
    def isUndeferred(asf: universe.Type) = ! isDeferred(asf)

    override def toString = name
  }

  /** Turns a scala `Type` into an updatable `Type`. */
  implicit def toTpe(tpe: universe.Type): Tpe

  /** Turns a scala `Symbol` into an attribute `Attribute`. */
  implicit def toAtt(sym: universe.Symbol): Att

  /* Some AttTpe method signatures are conflicting with Type ones, so we won't
   * implement this method as a view.
   */
  def toAttTpe(tpe: universe.Type): AttTpe
}

/** Does bring metamodel features to macro-land.
  *
  * This kind of metamodel requests a macro context and uses its associated
  * universe to fulfill the reflection needs. One of the specific tasks that
  * this metamodel should implement is to look for builders at the macro
  * invocation scope.
  */
trait MacroMetaModel extends MetaModelAPI {
  type Tpe = Type
  type AttTpe = AttributeType
  type Att = Attribute

  val c2: Context
  val universe: c2.universe.type = c2.universe

  /** Does contain macro-specific metainformation about types.
    *
    * @param tpe the entity type
    */
  class Type(tpe: universe.Type) extends TypeAPI(tpe) {

    /** Looks for an implicit value of `Builder[tpe]`.
      *
      * @return an option value containing the builder (as `Tree`), or `None` if
      * no builder found.
      */
    def builder(asf: universe.Type): Option[universe.Tree] = {
      val btpe = universe.appliedType(
	universe.typeOf[WeakBuilder[Any]], 
	List(tpe.asSeenFrom(asf, tpe.typeSymbol)))
      val builder = c2.inferImplicitValue(btpe)
      if (builder == universe.EmptyTree) {
  	c2.warning(
  	  c2.enclosingPosition,
  	  s"A builder for ${tpe.name} was not found, so type name will be " +
  	  "used in order to find the attribute's reifications.")
  	None
      } else
	Some(builder)
    }

    override def equals(other: Any) = other match {
      case t: Type => tpe == t.tpe
      case _ => false
    }
  }

  class AttributeType(_tpe: universe.Type) extends AttributeTypeAPI(_tpe)

  class Attribute(sym: universe.Symbol) extends AttributeAPI(sym) {

    def default: Option[_] = None

    override def equals(other: Any) = other match {
      case a: Attribute => sym == a.sym
      case _ => false
    }
  }

  implicit def toTpe(tpe: universe.Type): Tpe = new Type(tpe)

  def toAttTpe(tpe: universe.Type): AttTpe = new AttributeType(tpe)

  implicit def toAtt(sym: universe.Symbol): Att = new Attribute(sym)
}

/** Does bring metamodel features to runtime.
  *
  * This kind of metamodel uses the scala runtime reflection to fulfil the
  * reflection needs. This time the metamodel is available along with the
  * user code, so some methods to alter instance values are introduced, such as
  * `get` and `updated` inside runtime attributes.
  *
  * This metamodel can be created by using the companion object's apply method.
  */
trait RuntimeMetaModel extends MetaModelAPI {
  import scala.tools.reflect.ToolBox

  type Tpe = Type
  type AttTpe = AttributeType
  type Att = Attribute

  val mirror = scala.reflect.runtime.currentMirror
  val universe = mirror.universe
  val toolBox = mirror.mkToolBox()

  class Type(tpe: universe.Type) extends TypeAPI(tpe) {
    override def equals(other: Any) = other match {
      case t: Type => tpe == t.tpe
      case _ => false
    }
  }

  class AttributeType(_tpe: universe.Type) extends AttributeTypeAPI(_tpe)

  class Attribute(sym: universe.Symbol) extends AttributeAPI(sym) {
    type Owner

    def default: Option[Any] = {
      import universe._
      import toolBox. { eval, resetLocalAttrs, resetAllAttrs }

      sym.typeSignature match {
	case NullaryMethodType(
	  AnnotatedType(List(annot), tpe, _)) => {
	    val tree = annot.scalaArgs(0)
	    if (! (tree.tpe <:< tpe))
	      throw new Error(
		s"Invalid default for '$name': '$tree' does not conform $tpe")
	    else
	      // SI-5748, SI-5464
	      Option(eval(resetAllAttrs(tree)))
	  }
	case _ => None
      }
    }

    /** Returns the value of this attribute in `t`.
      *
      * @tparam A the entity type
      * @param t the entity instance
      */
    def get[A: Builder](t: A): Any = ibuilder.get(t, this)

    /** Returns a copy of `t`, but updating this attribute with the value `v`. 
      *
      * @tparam A the entity type
      * @param t the entity instance
      * @param v the new value to set in this attribute
      */
    def updated[A: Builder](t: A, v: Any): A = ibuilder.updated(t, this, v)

    override def equals(other: Any) = other match {
      case a: Attribute => sym == a.sym
      case _ => false
    }
  }

  implicit def toTpe(tpe: universe.Type): Tpe = new Type(tpe)

  def toAttTpe(tpe: universe.Type): AttTpe = new AttributeType(tpe)

  implicit def toAtt(sym: universe.Symbol): Att = new Attribute(sym)
}

/** Factory for [[org.hablapps.updatable.RuntimeMetaModel]] instances. */
object RuntimeMetaModel {
  def apply = new RuntimeMetaModel {}
}
