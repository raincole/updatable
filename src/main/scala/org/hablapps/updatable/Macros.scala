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

import scala.reflect.macros.Context
import language.experimental.macros

/** Contains some macro implementations. */
object Macros {

  /** Returns the expression that creates the helper.
    *
    * Parses the input to extract the involved entity, updatable and attribute.
    * Then, the instruction that instantiates the helper is created, taking
    * care of the type that must be returned, either UpdatedHelper or
    * ModifyHelper. Finally, the instruction is hand crafted and returned to
    * the user.
    * 
    * For invocation and usage examples, please refer to the declaration
    * documentation at [[org.hablapps.updatable.toUpdatedHelper]] and
    * [[org.hablapps.updatable.toModifyHelper]] instead.
    *
    * @tparam V the type of the value
    * @tparam H the expected kind of helper that this should return
    * @param c the macro context
    * @param v the value expression
    * @return the expression that creates the helper instance
    */
  def toAttributeHelperImpl[V: c.WeakTypeTag, H: c.WeakTypeTag](
      c: Context)(v: c.Expr[V]): c.Expr[H] = {
    import c.mirror._
    import c.universe._

    val hTpe = weakTypeTag[H].tpe 
    val vTpe = weakTypeTag[V].tpe

    def parseInput = v.tree match {
      case Select(qua @ Apply(
	TypeApply(Select(_, view), _),
	List(updatable)), sel) if view.toString == "fromUpdatable" => {
          (qua, updatable, Literal(Constant(sel.toString)))
      }
      case Select(qua, sel) => {
        val builder = c.inferImplicitValue(
	  appliedType(typeOf[Builder[_]], List(qua.tpe.widen)), false)
        val updatable = 
	  Apply(Apply(Ident("toUpdatable"), List(qua)), List(builder))

	(qua, updatable, Literal(Constant(sel.decoded)))
      }
      case _ => {
        c.abort(
	  c.enclosingPosition, 
	  s"Expression must match 'Select(ent, att)', found: ${showRaw(v.tree)}")
      }
    }

    val (entity, updatable, attribute) = parseInput
    val _tpe = hTpe match { 
      case t if (t =:= typeOf[ModifyHelper]) => vTpe match {
	case TypeRef(_, _, args) if (args.length > 0) => args(0).widen
	case _ => {
	  c.abort(
	    c.enclosingPosition, 
	    s"Simple attributes, like '$attribute', cannot be modified")
	}
      }
      case t if (t =:= typeOf[UpdatedHelper]) => vTpe.widen
    }

    def isFinalType(t: universe.Type): Boolean = {

      def cond = entity.tpe.members.toList.contains(t.typeSymbol) ||
        t.widen.typeSymbol.isFinal

      if (t.typeConstructor.takesTypeArgs)
	cond && isFinalList(t.asInstanceOf[universe.TypeRef].args)
      else
	cond
    }

    def isFinalList(args: List[universe.Type]): Boolean =
      (args find { ! isFinalType(_) }).isEmpty

    if (entity.tpe.typeSymbol.asType.isParameter && (! isFinalType(vTpe)))
      c.echo(c.enclosingPosition, "Are you sure you want to modify/update " +
        "a non-final attribute? Doing so could lead to casting failures.")

    c.Expr[H](
      Apply(
      TypeApply(
	Select(
	  (if (hTpe =:= typeOf[UpdatedHelper])
	    Ident(newTermName("UpdatedHelper"))
	  else if (hTpe =:= typeOf[ModifyHelper])
	    Ident(newTermName("ModifyHelper"))
	  else
	    c.abort(
	      c.enclosingPosition,
	      s"'$hTpe' is not a valid CommonHelper")), 
	  newTermName("apply")),
	List(TypeTree(entity.tpe.widen), TypeTree(_tpe))),
      List(attribute, updatable)))
  }
  
  /** Returns an instance of `WeakBuilder` for type `A`.
    *
    * Integrates the metamodel module (to extract the type information) with the
    * instruction maker (to generate the expression that will be parsed) and
    * generates the demanded code.
    *
    * Please, refer to [[org.hablapps.updatable.MkBuilder]] to see the raw code
    * that is being generated by `mkWeakBuilder`. For invocation and usage
    * examples go to [[org.hablapps.updatable.weakBuilder]].
    * 
    * @tparam A the type that is reflected to generate the builder
    * @param c the macro context
    * @return the weakBuilder for type `A`
    * @see [[org.hablapps.updatable.MkBuilder]]
    */
  def weakBuilderImpl[A: c.WeakTypeTag](c: Context) = {
    import c.mirror._
    import c.universe._

    val mk = new {
      val c2: c.type = c
    } with MacroMetaModel with MkBuilder { 
      val tpe = c2.weakTypeOf[A]
    }
    c.Expr[WeakBuilder[A]](c.parse(s"{ val aux = ${mk.mkWeakBuilder}; aux }"))
  }

  /** Returns an instance of `Builder` for type `A`.
    *
    * Integrates the metamodel module (to extract the type information) with the
    * instruction maker (to generate the expression that will be parsed) and
    * generates the demanded code.
    *
    * Please, refer to [[org.hablapps.updatable.MkBuilder]] to see the raw code
    * that is being generated by `mkBuilder`. For invocation and usage
    * examples go to [[org.hablapps.updatable.builder]].
    * 
    * @tparam A the type that is reflected to generate the builder
    * @param c the macro context
    * @return the weakBuilder for type `A`
    * @see [[org.hablapps.updatable.MkBuilder]]
    */
  def builderImpl[A: c.WeakTypeTag](c: Context) = { 
    import c.mirror._
    import c.universe._

    val mk = new {
      val c2: c.type = c
    } with MacroMetaModel with MkBuilder {
      val tpe = c2.weakTypeOf[A]
    }
    c.Expr[Builder[A]](c.parse(s"{ val aux = ${mk.mkBuilder}; aux }"))
  }

  /** Returns a map with attributes as keys and corresponding evidences as
    * values.
    *
    * Please, refer to [[org.hablapps.updatable.MkAttributeEvidences]] to see
    * the raw code that is being generated by `mkMap`. For invocation and
    * usage examples go to [[org.hablapps.updatable.attributeEvidences]].
    * 
    * @tparam Evid the type of the evidence user is looking for
    * @tparam A the type of the entity
    * @param c the macro context
    * @param b the builder that contains the attribute reifications
    * @return the map that contain the evidences for each attribute
    * @see [[org.hablapps.updatable.MkAttributeEvidences]]
    */
  def attributeEvidencesImpl[Evid: c.WeakTypeTag, A: c.WeakTypeTag](c: Context)(
      b: c.Expr[Builder[A]]): c.Expr[Map[model.Attribute, EvidenceTag]] = {
    import c.mirror._
    import c.universe._

    val mk = new {
      val c2: c.type = c
    } with MacroMetaModel with MkAttributeEvidences {
      val eTpe = c2.weakTypeOf[Evid]
      val tTpe = c2.weakTypeOf[A]
      val builder = b.tree
    }
    c.Expr[Map[model.Attribute, EvidenceTag]](mk.mkMap)
  }
}
