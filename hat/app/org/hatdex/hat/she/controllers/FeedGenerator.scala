/*
 * Copyright (C) 2017 HAT Data Exchange Ltd
 * SPDX-License-Identifier: AGPL-3.0
 *
 * This file is part of the Hub of All Things project (HAT).
 *
 * HAT is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, version 3 of
 * the License.
 *
 * HAT is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General
 * Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 *
 * Written by Andrius Aucinas <andrius.aucinas@hatdex.org>
 * 11 / 2017
 */

package org.hatdex.hat.she.controllers

import javax.inject.Inject

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import com.mohiva.play.silhouette.api.Silhouette
import org.hatdex.hat.api.json.{ DataFeedItemJsonProtocol, RichDataJsonFormats }
import org.hatdex.hat.api.models._
import org.hatdex.hat.api.models.applications.DataFeedItem
import org.hatdex.hat.api.service.richData.RichDataService
import org.hatdex.hat.authentication.{ HatApiAuthEnvironment, HatApiController, WithRole }
import org.hatdex.hat.resourceManagement.HatServer
import org.hatdex.hat.she.models.FunctionConfigurationJsonProtocol
import org.hatdex.hat.she.service._
import org.hatdex.hat.utils.SourceMergeSorter
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc._

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

class FeedGenerator @Inject() (
    components: ControllerComponents,
    silhouette: Silhouette[HatApiAuthEnvironment])(
    implicit
    richDataService: RichDataService,
    val actorSystem: ActorSystem,
    val ec: ExecutionContext)
  extends HatApiController(components, silhouette)
  with RichDataJsonFormats
  with FunctionConfigurationJsonProtocol
  with DataFeedItemJsonProtocol {

  private val logger = Logger(this.getClass)
  protected implicit val materializer: ActorMaterializer = ActorMaterializer()

  private val dataMappers: Seq[(String, DataEndpointMapper)] = Seq(
    "facebook/feed" → new FacebookFeedMapper(),
    "facebook/events" → new FacebookEventMapper(),
    "twitter/tweets" → new TwitterFeedMapper(),
    "fitbit/sleep" → new FitbitSleepMapper(),
    "fitbit/weight" → new FitbitWeightMapper(),
    "fitbit/activity" → new FitbitActivityMapper(),
    "fitbit/activity/day/summary" → new FitbitActivityDaySummaryMapper(),
    "calendar/google/events" → new GoogleCalendarMapper(),
    "notables/feed" → new NotablesFeedMapper(),
    "spotify/feed" -> new SpotifyFeedMapper())

  def getFeed(endpoint: String, since: Option[Long], until: Option[Long]): Action[AnyContent] =
    SecuredAction(WithRole(Owner())).async { implicit request =>
      feedForMappers(dataMappers.filter(_._1.startsWith(endpoint)), since, until)
        .map { items ⇒
          Ok(Json.toJson(items))
        }
    }

  def fullFeed(since: Option[Long], until: Option[Long]): Action[AnyContent] =
    SecuredAction(WithRole(Owner())).async { implicit request =>
      feedForMappers(dataMappers, since, until)
        .map { items ⇒
          Ok(Json.toJson(items))
        }
    }

  private val defaultTimeBack = 180.days
  private val defaultTimeForward = 90.days
  protected def feedForMappers(mappers: Seq[(String, DataEndpointMapper)], since: Option[Long], until: Option[Long])(
    implicit
    hatServer: HatServer): Future[Seq[DataFeedItem]] = {
    logger.debug(s"Fetching feed data for ${mappers.unzip._1}")
    val now = DateTime.now()
    val sources: Seq[Source[DataFeedItem, NotUsed]] = mappers
      .unzip._2
      .map {
        _.feed(
          since.map(t ⇒ new DateTime(t * 1000L)).orElse(Some(now.minus(defaultTimeBack.toMillis))),
          until.map(t ⇒ new DateTime(t * 1000L)).orElse(Some(now.plus(defaultTimeForward.toMillis))))
      }

    new SourceMergeSorter()
      .mergeWithSorter(sources)
      .runWith(Sink.seq)
  }

  protected implicit def dataFeedItemOrdering: Ordering[DataFeedItem] = Ordering.fromLessThan(_.date isAfter _.date)

}