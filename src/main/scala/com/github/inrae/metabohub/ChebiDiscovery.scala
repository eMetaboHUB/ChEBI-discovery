/**
 *
 */
package com.github.inrae.metabohub

import inrae.semantic_web.rdf.{QueryVariable, SparqlBuilder, URI}
import inrae.semantic_web.{SWDiscovery, StatementConfiguration}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.Dynamic
import scala.scalajs.js.JSConverters.{JSRichFutureNonThenable, JSRichIterableOnce}
import scala.scalajs.js.annotation.{JSExport, JSExportTopLevel}


@JSExportTopLevel(name="ChebiDiscovery")
case class ChebiDiscovery(
                           config_discovery : String = """{
             "sources" : [{
               "id"  : "Forum-Desease-Chem",
               "url" : "https://forum.semantic-metabolomics.fr/sparql"
             }],
             "settings" : {
               "logLevel" : "off"
             }
        }""".stripMargin

                         ) {

  val instDiscovery =
    SWDiscovery(StatementConfiguration.setConfigString(config_discovery))
      .prefix("chebiProp", "http://purl.obolibrary.org/obo/chebi#")

  /**
   * Give all level ancestor
   *
   * @param chebiIds  : List of ChEBI
   * @param deepLevel : branch level
   * @return
   */

  def ancestor_level(
                      chebiIds: Seq[URI],
                      deepLevel: Int = 1,
                      allAncestor: Boolean = false
                    )
  : Future[Seq[URI]] = {

    if (deepLevel <= 0) throw ChebiDiscoveryException("level must be strictly positive.")

    val listVariableAncestor: List[String] = allAncestor match {
      case false => List("ac_1")
      case true => deepLevel.to(1, -1).foldLeft(List[String]())((acc: List[String], ind: Int) => {
        acc ++ List("ac_" + ind)
      })
    }

    val listPossibleProperties: List[URI] =
      List("rdfs:subClassOf")

    val queryStart: SWDiscovery = instDiscovery
      .something("listOfInterest")
      .setList(chebiIds.distinct)

    deepLevel.to(1, -1).foldLeft(queryStart) {
      (query: SWDiscovery, ind: Int) => {
        query.isSubjectOf(QueryVariable("prop_" + ind), "ac_" + ind)
          .root
          .something("prop_" + ind)
          .setList(listPossibleProperties)
          .focus("ac_" + ind)
      }
    }
      .console
      .select(listVariableAncestor)
      .commit()
      .raw
      .map(json => json("results")("bindings")
        .arr.flatMap(v => listVariableAncestor.map(variable => SparqlBuilder.createUri(v(variable)))).toSeq)
  }

  @JSExport("ancestor_level")
  def ancestor_level_js(chebiIds: js.Array[String],
                        deepLevel: Int = 1,
                        allAncestor: Boolean = false): js.Promise[js.Array[String]] = {

    ancestor_level(
      chebiIds.toList.map(st => URI(st)),
      deepLevel,
      allAncestor)
      .map(lUris => lUris.map(uri => uri.localName).toJSArray).toJSPromise
  }

  def lowest_common_ancestor(
                              chebiIds: Seq[URI],
                              startNbLevel: Int = 1,
                              stepNbLevel: Int = 1,
                              maxDeepLevel: Int = 8,
                            ): Future[Seq[URI]] = {

    if (startNbLevel > maxDeepLevel) throw ChebiDiscoveryException(
      s"""
         the search depth has reached its maximum.
         startNbLevel -> $startNbLevel,
         stepNbLevel  -> $stepNbLevel,
         maxDeepLevel -> $maxDeepLevel,
         """.stripMargin)

    Future.sequence(chebiIds.map(chebid => {
      ancestor_level(chebiIds = List(chebid), deepLevel = startNbLevel, true)
    })).map(
      ancestorsListByChebId => {
        ancestorsListByChebId.foldLeft(ancestorsListByChebId(0))(
          (accumulator: Seq[URI], ancestorFromAChebId: Seq[URI]) => {
            /* keep only common uri */
            ancestorFromAChebId.filter(uri => accumulator.contains(uri))
          }
        )
      }
    ) map {
      case empty if empty.length <= 0 => lowest_common_ancestor(chebiIds, startNbLevel + stepNbLevel, stepNbLevel, maxDeepLevel)
      case lca => Future {
        lca
      }
    }
  }.flatten

  @JSExport("lowest_common_ancestor")
  def lowest_common_ancestor_js(
                                 chebiIds: js.Array[String],
                                 startNbLevel: Int = 1,
                                 stepNbLevel: Int = 1,
                                 maxDeepLevel: Int = 8,
                               ): js.Promise[js.Array[String]] =
    lowest_common_ancestor(
      chebiIds.toList.map(s => URI(s)),
      startNbLevel,
      stepNbLevel,
      maxDeepLevel
    ).map(lUris => lUris.map(uri => uri.localName).toJSArray).toJSPromise


  def ontology_based_matching_static_level(
                                            chebiIds: Seq[URI],
                                            deepLevel: Int = 1
                                          )
  : Future[Map[URI, Map[String, URI]]] = {
    if (deepLevel <= 0) throw ChebiDiscoveryException("level must be strictly positive.")

    val listOwlOnProperties: List[URI] =
      List(
        "chebiProp:is_conjugate_acid_of",
        "chebiProp:is_conjugate_base_of",
        "chebiProp:has_functional_parent",
        "chebiProp:is_tautomer_of",
        "chebiProp:has_parent_hydride",
        "chebiProp:is_substituent_group_from",
        "chebiProp:is_enantiomer_of")

    //"rdfs:subClassOf"
    /*
    <http://purl.obolibrary.org/obo/CHEBI_15756> rdfs:subClassOf ?something .
?something <http://www.w3.org/2002/07/owl#onProperty> ?t .
?something ?prop <http://purl.obolibrary.org/obo/CHEBI_7896> .
     */
    val variables: Seq[String] = (1).to(deepLevel).map(level => "prop_" + level) ++ (1).to(deepLevel).map(level => "ac_" + level)

    val queryStart: SWDiscovery = instDiscovery
      .something("chebi_start")
      .setList(chebiIds.distinct)

    Future.sequence(List(
      deepLevel.to(1, -1).foldLeft(queryStart) {
        (query: SWDiscovery, ind: Int) => {
          query.isSubjectOf(URI("rdfs:subClassOf"), "ac_" + ind)
        }
      }
        .setList(chebiIds.distinct)
        .select(List("chebi_start", "chebi_end"))
        .commit()
        .raw
        .map(json =>
          json("results")("bindings").arr.map(row => {
            val chebi_start = SparqlBuilder.createUri(row("chebi_start"))
            val chebi_end = SparqlBuilder.createUri(row("chebi_end"))
            chebi_start -> Map("is_a" -> chebi_end)
          }).toMap
        )
      ,

      deepLevel.to(2, -1).foldLeft(queryStart) {
        (query: SWDiscovery, ind: Int) => {
          query.isSubjectOf(URI("rdfs:subClassOf"), "ac_" + ind)
        }
      }
        .isSubjectOf(URI("rdfs:subClassOf"), "ac_1")
        .isSubjectOf(URI("owl:onProperty"), "prop_1")
        .focus("ac_1")
        .isSubjectOf(URI("owl:someValuesFrom"), "chebi_end")
        .setList(chebiIds.distinct)
        .filter.not.equal(QueryVariable("chebi_start"))
        .root
        .something("prop_1")
        .setList(listOwlOnProperties)
        .select(List("chebi_start", "chebi_end", "prop_1"))
        .commit()
        .raw
        .map(json =>
          json("results")("bindings").arr.map(row => {
            val chebi_start = SparqlBuilder.createUri(row("chebi_start"))
            val chebi_end = SparqlBuilder.createUri(row("chebi_end"))
            val prop = SparqlBuilder.createUri(row("prop_1")).localName.split("#")(1)
            chebi_start -> Map(prop -> chebi_end)
          }).toMap
        )
    )).map(ll => ll.reduce((x, y) => (x ++ y)))
  }

  def ontology_based_matching(
                                            chebiIds: Seq[URI],
                                            maxScore: Double = 4.5
                                          )
  : Future[Seq[(URI,URI,String,Double)]] = {

    if (maxScore <= 0.0) throw ChebiDiscoveryException(
      s"""
         maxScore must be positive .
         maxScore -> $maxScore

         rules:
         ------
         1.0 -> is_a relation
         0.1 -> is_conjugate_acid_of,is_conjugate_base_of,
                has_functional_parent,is_tautomer_of,
                has_parent_hydride,is_substituent_group_from,
                is_enantiomer_of

         """.stripMargin)

    val deepestLength = Math.round(maxScore)
    Future.sequence(deepestLength.to(1, -1).map(
      deepLevel => ontology_based_matching_static_level(chebiIds, deepLevel.toInt)
    )).map(
      (lm : Seq[Map[URI, Map[String, URI]]]) => {
        lm.zipWithIndex.flatMap( { case (m,i) =>
        m.flatMap(v1 => {
          v1._2.map(v2=> {
            v2._1 match {
              case "is_a" => (v1._1,v2._2,v2._1,1.0+(deepestLength-i-1))
              case _ => (v1._1,v2._2,v2._1,0.1+(deepestLength-i-1))
            }
          })
        }).toSeq })
      })
    }

  @JSExport("ontology_based_matching")
  def ontology_based_matching_js(
                                  chebiIds: js.Array[String],
                                  maxScore: Double = 4.5
                               ): js.Promise[js.Array[js.Object with js.Dynamic]] =
    ontology_based_matching(
      chebiIds.toList.map(s => URI(s)),
      maxScore
    ).map(lTuples => lTuples.map(tuple =>  Dynamic.literal(
      "uri1" -> tuple._1.localName,
      "uri2" -> tuple._2.localName,
      "property" -> tuple._3,
      "score" -> tuple._4)
    ).toJSArray).toJSPromise
}