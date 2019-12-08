package lila.user

import org.joda.time.DateTime
import reactivemongo.api._
import reactivemongo.api.bson._

import lila.common.{ ApiVersion, EmailAddress, NormalizedEmailAddress }
import lila.db.BSON.BSONJodaDateTimeHandler
import lila.db.dsl._
import lila.rating.{ Perf, PerfType }

final class UserRepo(val coll: Coll) {

  import User.userBSONHandler

  import User.ID
  import User.{ BSONFields => F }
  import Title.titleBsonHandler

  def withColl[A](f: Coll => A): A = f(coll)

  val normalize = User normalize _

  def topNbGame(nb: Int): Fu[List[User]] =
    coll.ext.find(enabledSelect).sort($sort desc "count.game").list[User](nb)

  def byId(id: ID): Fu[Option[User]] = coll.byId[User](id)

  def byIds(ids: Iterable[ID]): Fu[List[User]] = coll.byIds[User](ids)

  def byIdsSecondary(ids: Iterable[ID]): Fu[List[User]] = coll.byIds[User](ids, ReadPreference.secondaryPreferred)

  def byEmail(email: NormalizedEmailAddress): Fu[Option[User]] = coll.one[User]($doc(F.email -> email))
  def byPrevEmail(
    email: NormalizedEmailAddress,
    readPreference: ReadPreference = ReadPreference.secondaryPreferred
  ): Fu[List[User]] =
    coll.list[User]($doc(F.prevEmail -> email), readPreference)

  def idByEmail(email: NormalizedEmailAddress): Fu[Option[String]] =
    coll.primitiveOne[String]($doc(F.email -> email), "_id")

  def idCursor(
    selector: Bdoc,
    batchSize: Int = 0,
    readPreference: ReadPreference = ReadPreference.secondaryPreferred
  )(implicit cp: CursorProducer[Bdoc]): cp.ProducedCursor = {
    coll.ext.find(selector)
      .batchSize(batchSize)
      .cursor[Bdoc](readPreference)
  }

  def countRecentByPrevEmail(
    email: NormalizedEmailAddress,
    since: DateTime = DateTime.now.minusWeeks(1)
  ): Fu[Int] =
    coll.countSel($doc(F.prevEmail -> email, F.createdAt $gt since))

  def pair(x: Option[ID], y: Option[ID]): Fu[(Option[User], Option[User])] =
    coll.byIds[User](List(x, y).flatten) map { users =>
      x.??(xx => users.find(_.id == xx)) ->
        y.??(yy => users.find(_.id == yy))
    }

  def pair(x: ID, y: ID): Fu[Option[(User, User)]] =
    coll.byIds[User](List(x, y)) map { users =>
      for {
        xx <- users.find(_.id == x)
        yy <- users.find(_.id == y)
      } yield xx -> yy
    }

  def byOrderedIds(ids: Seq[ID], readPreference: ReadPreference): Fu[List[User]] =
    coll.byOrderedIds[User, User.ID](ids, readPreference = readPreference)(_.id)

  def idsMap(ids: Seq[ID], readPreference: ReadPreference = ReadPreference.secondaryPreferred): Fu[Map[User.ID, User]] =
    coll.idsMap[User, User.ID](ids, readPreference)(_.id)

  def usersFromSecondary(userIds: Seq[ID]): Fu[List[User]] =
    byOrderedIds(userIds, ReadPreference.secondaryPreferred)

  def enabledByIds(ids: Iterable[ID]): Fu[List[User]] =
    coll.list[User](enabledSelect ++ $inIds(ids), ReadPreference.secondaryPreferred)

  def enabledById(id: ID): Fu[Option[User]] =
    coll.one[User](enabledSelect ++ $id(id))

  def named(username: String): Fu[Option[User]] = coll.byId[User](normalize(username))

  def nameds(usernames: List[String]): Fu[List[User]] = coll.byIds[User](usernames.map(normalize))

  // expensive, send to secondary
  def byIdsSortRatingNoBot(ids: Iterable[ID], nb: Int): Fu[List[User]] =
    coll.ext.find($inIds(ids) ++ goodLadSelectBson ++ botSelect(false))
      .sort($sort desc "perfs.standard.gl.r")
      .list[User](nb, ReadPreference.secondaryPreferred)

  //   // expensive, send to secondary
  //   def ratedIdsByIdsSortRating(ids: Iterable[ID], nb: Int): Fu[List[User.ID]] =
  //     coll.find(
  //       $inIds(ids) ++ goodLadSelectBson ++ stablePerfSelect("standard"),
  //       $id(true)
  //     )
  //       .sort($sort desc "perfs.standard.gl.r")
  //       .list[Bdoc](nb, ReadPreference.secondaryPreferred).map {
  //         _.flatMap { _.getAs[User.ID]("_id") }
  //       }

  // private[user] def allSortToints(nb: Int) =
  //   coll.find($empty).sort($sort desc F.toints).list[User](nb)

  def usernameById(id: ID) =
    coll.primitiveOne[User.ID]($id(id), F.username)

  def usernamesByIds(ids: List[ID]) =
    coll.distinctEasy[String, List](F.username, $inIds(ids))

  def createdAtById(id: ID) =
    coll.primitiveOne[DateTime]($id(id), F.createdAt)

  def orderByGameCount(u1: User.ID, u2: User.ID): Fu[Option[(User.ID, User.ID)]] = {
    coll.ext.find(
      $inIds(List(u1, u2)),
      $doc(s"${F.count}.game" -> true)
    ).list[Bdoc]() map { docs =>
        docs.sortBy {
          _.child(F.count).flatMap(_.int("game"))
        }.map(_.string("_id")).flatten match {
          case List(u1, u2) => (u1, u2).some
          case _ => none
        }
      }
  }

  def firstGetsWhite(u1: User.ID, u2: User.ID): Fu[Boolean] = coll.ext.find(
    $inIds(List(u1, u2)),
    $id(true)
  ).sort($doc(F.colorIt -> 1)).one[Bdoc].map {
      _.fold(scala.util.Random.nextBoolean) { doc =>
        doc.string("_id") contains u1
      }
    }.addEffect { v =>
      incColor(u1, if (v) 1 else -1)
      incColor(u2, if (v) -1 else 1)
    }

  def firstGetsWhite(u1O: Option[User.ID], u2O: Option[User.ID]): Fu[Boolean] =
    (u1O |@| u2O).tupled.fold(fuccess(scala.util.Random.nextBoolean)) {
      case (u1, u2) => firstGetsWhite(u1, u2)
    }

  def incColor(userId: User.ID, value: Int): Unit =
    coll.update(false, WriteConcern.Unacknowledged).one(
      $id(userId) ++ (value < 0).??($doc("colorIt" $gt -3)),
      $inc(F.colorIt -> value)
    )

  def lichess = byId(User.lichessId)

  val irwinId = "irwin"
  def irwin = byId(irwinId)

  def setPerfs(user: User, perfs: Perfs, prev: Perfs) = {
    val diff = PerfType.all flatMap { pt =>
      perfs(pt).nb != prev(pt).nb option {
        BSONElement(
          s"${F.perfs}.${pt.key}", Perf.perfBSONHandler.write(perfs(pt))
        )
      }
    }
    diff.nonEmpty ?? coll.update.one(
      $id(user.id),
      $doc("$set" -> $doc(diff: _*))
    ).void
  }

  def setPerf(userId: String, pt: PerfType, perf: Perf) =
    coll.update.one($id(userId), $set(
      s"${F.perfs}.${pt.key}" -> Perf.perfBSONHandler.write(perf)
    )).void

  def setProfile(id: ID, profile: Profile): Funit =
    coll.update.one(
      $id(id),
      $set(F.profile -> Profile.profileBSONHandler.writeTry(profile).get)
    ).void

  def setUsernameCased(id: ID, username: String): Funit = {
    if (id == username.toLowerCase) {
      coll.update.one(
        $id(id) ++ (F.changedCase $exists false),
        $set(F.username -> username, F.changedCase -> true)
      ) flatMap { result =>
          if (result.n == 0) fufail(s"You have already changed your username")
          else funit
        }
    } else fufail(s"Proposed username $username does not match old username $id")
  }

  def addTitle(id: ID, title: Title): Funit =
    coll.updateField($id(id), F.title, title).void

  def removeTitle(id: ID): Funit =
    coll.unsetField($id(id), F.title).void

  def setPlayTime(id: ID, playTime: User.PlayTime): Funit =
    coll.update.one($id(id), $set(F.playTime -> User.playTimeHandler.writeTry(playTime).get)).void

  def getPlayTime(id: ID): Fu[Option[User.PlayTime]] =
    coll.primitiveOne[User.PlayTime]($id(id), F.playTime)

  val enabledSelect = $doc(F.enabled -> true)
  def engineSelect(v: Boolean) = $doc(F.engine -> (if (v) $boolean(true) else $ne(true)))
  def trollSelect(v: Boolean) = $doc(F.troll -> (if (v) $boolean(true) else $ne(true)))
  def boosterSelect(v: Boolean) = $doc(F.booster -> (if (v) $boolean(true) else $ne(true)))
  def stablePerfSelect(perf: String) = $doc(s"perfs.$perf.gl.d" -> $lt(lila.rating.Glicko.provisionalDeviation))
  val goodLadSelect = enabledSelect ++ engineSelect(false) ++ boosterSelect(false)
  val goodLadSelectBson = $doc(
    F.enabled -> true,
    F.engine $ne true,
    F.booster $ne true
  )
  val patronSelect = $doc(s"${F.plan}.active" -> true)

  def sortPerfDesc(perf: String) = $sort desc s"perfs.$perf.gl.r"
  val sortCreatedAtDesc = $sort desc F.createdAt

  def incNbGames(id: ID, rated: Boolean, ai: Boolean, result: Int, totalTime: Option[Int], tvTime: Option[Int]) = {
    val incs: List[BSONElement] = List(
      "count.game".some,
      rated option "count.rated",
      ai option "count.ai",
      (result match {
        case -1 => "count.loss".some
        case 1 => "count.win".some
        case 0 => "count.draw".some
        case _ => none
      }),
      (result match {
        case -1 => "count.lossH".some
        case 1 => "count.winH".some
        case 0 => "count.drawH".some
        case _ => none
      }) ifFalse ai
    ).flatten.map(k => BSONElement(k, BSONInteger(1))) ::: List(
        totalTime map (v => BSONElement(s"${F.playTime}.total", BSONInteger(v + 2))),
        tvTime map (v => BSONElement(s"${F.playTime}.tv", BSONInteger(v + 2)))
      ).flatten

    coll.update.one($id(id), $inc($doc(incs: _*)))
  }

  def incToints(id: ID, nb: Int) = coll.update.one($id(id), $inc("toints" -> nb))
  def removeAllToints = coll.update.one($empty, $unset("toints"), multi = true)

  def create(
    username: String,
    passwordHash: HashedPassword,
    email: EmailAddress,
    blind: Boolean,
    mobileApiVersion: Option[ApiVersion],
    mustConfirmEmail: Boolean
  ): Fu[Option[User]] =
    !nameExists(username) flatMap {
      _ ?? {
        val doc = newUser(username, passwordHash, email, blind, mobileApiVersion, mustConfirmEmail) ++
          ("len" -> BSONInteger(username.size))
        coll.insert.one(doc) >> named(normalize(username))
      }
    }

  def nameExists(username: String): Fu[Boolean] = idExists(normalize(username))
  def idExists(id: String): Fu[Boolean] = coll exists $id(id)

  /**
   * Filters out invalid usernames and returns the IDs for those usernames
   *
   * @param usernames Usernames to filter out the non-existent usernames from, and return the IDs for
   * @return A list of IDs for the usernames that were given that were valid
   */
  def existingUsernameIds(usernames: Set[String]): Fu[List[User.ID]] =
    coll.primitive[String]($inIds(usernames.map(normalize)), F.id)

  def userIdsLike(text: String, max: Int = 10): Fu[List[User.ID]] =
    User.couldBeUsername(text) ?? {
      coll.ext.find(
        $doc(F.id $startsWith normalize(text)) ++ enabledSelect,
        $doc(F.id -> true)
      )
        .sort($doc("len" -> 1))
        .list[Bdoc](max, ReadPreference.secondaryPreferred)
        .map {
          _ flatMap { _.string(F.id) }
        }
    }

  def toggleEngine(id: ID): Funit =
    coll.fetchUpdate[User]($id(id)) { u =>
      $set(F.engine -> !u.engine)
    }

  def setEngine(id: ID, v: Boolean): Funit = coll.updateField($id(id), "engine", v).void

  def setBooster(id: ID, v: Boolean): Funit = coll.updateField($id(id), "booster", v).void

  def setReportban(id: ID, v: Boolean): Funit = coll.updateField($id(id), "reportban", v).void

  def setRankban(id: ID, v: Boolean): Funit = coll.updateField($id(id), "rankban", v).void

  def setIpBan(id: ID, v: Boolean) = coll.updateField($id(id), "ipBan", v).void

  def setKid(user: User, v: Boolean) = coll.updateField($id(user.id), "kid", v).void

  def isKid(id: ID) = coll.exists($id(id) ++ $doc("kid" -> true))

  def updateTroll(user: User) = coll.updateField($id(user.id), "troll", user.troll).void

  def isEngine(id: ID): Fu[Boolean] = coll.exists($id(id) ++ engineSelect(true))

  def isTroll(id: ID): Fu[Boolean] = coll.exists($id(id) ++ trollSelect(true))

  def setRoles(id: ID, roles: List[String]) = coll.updateField($id(id), "roles", roles)

  def disableTwoFactor(id: ID) = coll.update.one($id(id), $unset(F.totpSecret))

  def setupTwoFactor(id: ID, totp: TotpSecret): Funit =
    coll.update.one(
      $id(id) ++ (F.totpSecret $exists false), // never overwrite existing secret
      $set(F.totpSecret -> totp.secret)
    ).void

  def reopen(id: ID) = coll.updateField($id(id), F.enabled, true) >>
    coll.update.one(
      $id(id) ++ $doc(F.email $exists false),
      $doc("$rename" -> $doc(F.prevEmail -> F.email))
    ).void.recover(lila.db.recoverDuplicateKey(_ => ()))

  def disable(user: User, keepEmail: Boolean) = coll.update.one(
    $id(user.id),
    $set(F.enabled -> false) ++ $unset(F.roles) ++ {
      if (keepEmail) $unset(F.mustConfirmEmail)
      else $doc("$rename" -> $doc(F.email -> F.prevEmail))
    }
  )

  import Authenticator._
  def getPasswordHash(id: User.ID): Fu[Option[String]] =
    coll.byId[AuthData](id, authProjection) map {
      _.map { _.hashToken }
    }

  def setEmail(id: ID, email: EmailAddress): Funit = {
    val normalizedEmail = email.normalize
    coll.update.one(
      $id(id),
      $set(F.email -> normalizedEmail) ++ $unset(F.prevEmail) ++ {
        if (email.value == normalizedEmail.value) $unset(F.verbatimEmail)
        else $set(F.verbatimEmail -> email)
      }
    ).void
  }

  private def anyEmail(doc: Bdoc): Option[EmailAddress] =
    doc.getAsOpt[EmailAddress](F.verbatimEmail) orElse doc.getAsOpt[EmailAddress](F.email)

  private def anyEmailOrPrevious(doc: Bdoc): Option[EmailAddress] =
    anyEmail(doc) orElse doc.getAsOpt[EmailAddress](F.prevEmail)

  def email(id: ID): Fu[Option[EmailAddress]] = coll.ext.find(
    $id(id),
    $doc(
      F.email -> true,
      F.verbatimEmail -> true
    )
  ).one[Bdoc].map { _ ?? anyEmail }

  def enabledWithEmail(email: NormalizedEmailAddress): Fu[Option[(User, EmailAddress)]] =
    coll.ext.find($doc(
      F.email -> email,
      F.enabled -> true
    )).one[Bdoc].map { maybeDoc =>
      for {
        doc <- maybeDoc
        storedEmail <- anyEmail(doc)
      } yield (userBSONHandler read doc, storedEmail)
    }

  def withEmails(name: String): Fu[Option[User.WithEmails]] =
    coll.ext.find($id(normalize(name))).one[Bdoc].map {
      _ ?? { doc =>
        User.WithEmails(
          userBSONHandler read doc,
          User.Emails(
            current = anyEmail(doc),
            previous = doc.getAsOpt[NormalizedEmailAddress](F.prevEmail)
          )
        ).some
      }
    }

  def withEmails(names: List[String]): Fu[List[User.WithEmails]] =
    coll.ext.find($inIds(names map normalize))
      .list[Bdoc](none, ReadPreference.secondaryPreferred).map {
        _ map { doc =>
          User.WithEmails(
            userBSONHandler read doc,
            User.Emails(
              current = anyEmail(doc),
              previous = doc.getAsOpt[NormalizedEmailAddress](F.prevEmail)
            )
          )
        }
      }
  def withEmailsU(users: List[User]): Fu[List[User.WithEmails]] = withEmails(users.map(_.id))

  def emailMap(names: List[String]): Fu[Map[User.ID, EmailAddress]] =
    coll.ext.find($inIds(names map normalize), $doc(F.verbatimEmail -> true, F.email -> true, F.prevEmail -> true))
      .list[Bdoc](none, ReadPreference.secondaryPreferred).map { docs =>
        docs.view.flatMap { doc =>
          anyEmailOrPrevious(doc) map { ~doc.getAsOpt[User.ID](F.id) -> _ }
        }.to(Map)
      }

  def hasEmail(id: ID): Fu[Boolean] = email(id).map(_.isDefined)

  def setBot(user: User): Funit =
    if (user.count.game > 0) fufail("You already have games played. Make a new account.")
    else coll.updateField($id(user.id), F.title, Title.BOT).void

  private def botSelect(v: Boolean) =
    if (v) $doc(F.title -> Title.BOT)
    else $doc(F.title -> $ne(Title.BOT))

  private[user] def botIds = coll.secondaryPreferred.distinctEasy[String, Set](
    "_id",
    botSelect(true) ++ enabledSelect
  )

  def getTitle(id: ID): Fu[Option[Title]] = coll.primitiveOne[Title]($id(id), F.title)

  def setPlan(user: User, plan: Plan): Funit = {
    implicit val pbw: BSONWriter[Plan] = Plan.planBSONHandler
    coll.updateField($id(user.id), "plan", plan).void
  }

  private def docPerf(doc: Bdoc, perfType: PerfType): Option[Perf] =
    doc.child(F.perfs).flatMap(_.getAsOpt[Perf](perfType.key))

  def perfOf(id: ID, perfType: PerfType): Fu[Option[Perf]] = coll.ext.find(
    $id(id),
    $doc(s"${F.perfs}.${perfType.key}" -> true)
  ).one[Bdoc].map {
      _.flatMap { docPerf(_, perfType) }
    }

  def perfOf(ids: Iterable[ID], perfType: PerfType): Fu[Map[ID, Perf]] = coll.ext.find(
    $inIds(ids),
    $doc(s"${F.perfs}.${perfType.key}" -> true)
  ).cursor[Bdoc]()
    .collect[List](Int.MaxValue, err = Cursor.FailOnError[List[Bdoc]]()).map { docs =>
      docs.view.map { doc =>
        ~doc.getAsOpt[ID]("_id") -> docPerf(doc, perfType).getOrElse(Perf.default)
      }.to(Map)
    }

  def setSeenAt(id: ID): Unit =
    coll.updateFieldUnchecked($id(id), F.seenAt, DateTime.now)

  def recentlySeenNotKidIdsCursor(since: DateTime)(implicit cp: CursorProducer[Bdoc]) =
    coll.ext.find($doc(
      F.enabled -> true,
      "seenAt" $gt since,
      "count.game" $gt 9,
      "kid" $ne true
    ), $id(true)).cursor[Bdoc](readPreference = ReadPreference.secondary)

  def setLang(id: ID, lang: String) = coll.updateField($id(id), "lang", lang).void

  def langOf(id: ID): Fu[Option[String]] = coll.primitiveOne[String]($id(id), "lang")

  // def idsSumToints(ids: Iterable[String]): Fu[Int] =
  //   ids.nonEmpty ?? coll.aggregateOne(
  //     Match($inIds(ids)),
  //     List(Group(BSONNull)(F.toints -> SumField(F.toints))),
  //     ReadPreference.secondaryPreferred
  //   ).map {
  //       _ flatMap { _.getAs[Int](F.toints) }
  //     }.map(~_)

  def filterByEnabledPatrons(userIds: List[User.ID]): Fu[Set[User.ID]] =
    coll.distinctEasy[String, Set](F.id, $inIds(userIds) ++ enabledSelect ++ patronSelect)

  def userIdsWithRoles(roles: List[String]): Fu[Set[User.ID]] =
    coll.distinctEasy[String, Set]("_id", $doc("roles" $in roles))

  def countEngines(userIds: List[User.ID]): Fu[Int] =
    coll.secondaryPreferred.countSel($inIds(userIds) ++ engineSelect(true))

  def containsEngine(userIds: List[User.ID]): Fu[Boolean] =
    coll.exists($inIds(userIds) ++ engineSelect(true))

  def mustConfirmEmail(id: User.ID): Fu[Boolean] =
    coll.exists($id(id) ++ $doc(F.mustConfirmEmail $exists true))

  def setEmailConfirmed(id: User.ID): Fu[Option[EmailAddress]] =
    coll.update.one($id(id) ++ $doc(F.mustConfirmEmail $exists true), $unset(F.mustConfirmEmail)) flatMap { res =>
      (res.nModified == 1) ?? email(id)
    }

  def speaker(id: User.ID): Fu[Option[User.Speaker]] = {
    import User.speakerHandler
    coll.one[User.Speaker]($id(id))
  }

  def erase(user: User): Funit = coll.update.one(
    $id(user.id),
    $unset(F.profile) ++ $set(
      "enabled" -> false,
      "erasedAt" -> DateTime.now
    )
  ).void

  def isErased(user: User): Fu[User.Erased] = user.disabled ?? {
    coll.exists($id(user.id) ++ $doc("erasedAt" $exists true))
  } map User.Erased.apply

  private def newUser(
    username: String,
    passwordHash: HashedPassword,
    email: EmailAddress,
    blind: Boolean,
    mobileApiVersion: Option[ApiVersion],
    mustConfirmEmail: Boolean
  ) = {

    implicit def countHandler = Count.countBSONHandler
    import lila.db.BSON.BSONJodaDateTimeHandler

    val normalizedEmail = email.normalize
    $doc(
      F.id -> normalize(username),
      F.username -> username,
      F.email -> normalizedEmail,
      F.mustConfirmEmail -> mustConfirmEmail.option(DateTime.now),
      F.bpass -> passwordHash,
      F.perfs -> $empty,
      F.count -> Count.default,
      F.enabled -> true,
      F.createdAt -> DateTime.now,
      F.createdWithApiVersion -> mobileApiVersion.map(_.value),
      F.seenAt -> DateTime.now,
      F.playTime -> User.PlayTime(0, 0)
    ) ++ {
        (email.value != normalizedEmail.value) ?? $doc(F.verbatimEmail -> email)
      } ++ {
        if (blind) $doc("blind" -> true) else $empty
      }
  }
}
