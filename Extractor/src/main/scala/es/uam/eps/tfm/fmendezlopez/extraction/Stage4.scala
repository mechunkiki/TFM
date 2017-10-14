package es.uam.eps.tfm.fmendezlopez.extraction

import java.io.{File, IOException}
import java.sql.SQLException
import java.util.Properties

import com.github.tototoshi.csv.{CSVReader, CSVWriter}
import es.uam.eps.tfm.fmendezlopez.dao.DatabaseDAO
import es.uam.eps.tfm.fmendezlopez.dto.{Recipe, Review, User}
import es.uam.eps.tfm.fmendezlopez.scraping.Scraper
import es.uam.eps.tfm.fmendezlopez.utils._
import org.apache.commons.configuration2.Configuration
import org.json.{JSONArray, JSONObject}

/**
  * Created by franm on 25/06/2017.
  */
object Stage4 extends Logging{

  private var properties : Configuration = _
  private var connectionProperties : Properties = _

  private var csvUsers : CSVWriter = _
  private var csvRecipes : CSVWriter = _
  private var csvIngredients : CSVWriter = _
  private var csvReviews : CSVWriter = _
  private var csvSteps : CSVWriter = _
  private var csvNutrition : CSVWriter = _
  private var csvRelationshipUser : CSVWriter = _
  private var csvRelationshipRecipe : CSVWriter = _
  private var csvRecipesUrls : CSVWriter = _
  private var csvUsersUrls : CSVWriter = _

  def main(args: Array[String]): Unit = {

    if(args.length < 2){
      System.exit(1)
    }
    properties = PropertiesManager.loadProperties(args(0), PropertiesManager.EXTRACTION_PROPERTIES_FILE)

    HttpManager.setProperties(properties)
    Scraper.setProperties(properties)

    args(1).toInt match {
      case 1 =>
        val fromScratch = args(2).toBoolean
        val inputPath = args(3)

        var state : JSONObject = new JSONObject()
        if(fromScratch){
          state.put("currentFile", "")
          state.put("lastLine", 0)
          state.put("processedFiles", new JSONArray())
        }
        else{
          val statusFile : String = args(4)
          state = JSONManager.jsonFromFile(statusFile)
        }
        stage1(inputPath, state, fromScratch)
    }
  }

  def getProcessedFiles(status : JSONObject) : Seq[String] = {
    var result :Seq[String] = Seq()
    var i = 0
    val arr = status.getJSONArray("processedFiles")
    while(i < arr.length()){
      result :+= arr.getJSONObject(i).getString("name")
      i += 1
    }
    result
  }

  def stage1(inputPath : String, state : JSONObject, fromScratch : Boolean) = {

    connectionProperties = new Properties()
    connectionProperties.put("timeout", properties.getProperty("stage4.stage1.scraping.delay.timeout"))
    connectionProperties.put("max-body-size", properties.getProperty("stage4.stage1.scraping.maxBodySize"))
    connectionProperties.put("follow-redirects", properties.getProperty("stage4.stage1.scraping.followRedirects"))
    connectionProperties.put("api-host", properties.getProperty("allrecipes.api.host"))
    connectionProperties.put("base-host", properties.getProperty("allrecipes.url.base"))
    connectionProperties.put("host", properties.getProperty("allrecipes.host"))
    connectionProperties.put("attempts", properties.getProperty("stage4.stage1.scraping.attempts"))
    connectionProperties.put("delay-detection", properties.getProperty("stage4.stage1.scraping.delay.detection"))
    connectionProperties.put("delay-auth", properties.getProperty("stage4.stage1.scraping.delay.auth"))
    connectionProperties.put("referrer", properties.getProperty("general.scraping.referrer"))
    connectionProperties.put("max-pagesize", properties.getProperty("stage4.stage1.scraping.maxpagesize"))
    connectionProperties.put("delay-recipe", properties.getProperty("stage4.stage1.scraping.delay.recipe"))
    connectionProperties.put("delay-nrecipes", properties.getProperty("stage4.stage1.scraping.delay.nrecipes"))
    connectionProperties.put("nrecipes", properties.getProperty("stage4.stage1.scraping.nrecipes"))
    connectionProperties.put("delay-recipe-list", properties.getProperty("stage4.stage1.scraping.delay.recipe_list"))
    connectionProperties.put("max-times-301", properties.getProperty("general.extraction.http.max.times.301"))
    connectionProperties.put("default-category", properties.getProperty("stage4.stage1.scraping.recipe.category.default"))

    HttpManager.setProperties(properties)
    HttpManager.setConnectionProperties(connectionProperties)
    val res = HttpManager.requestAuthToken()
    if(res.isEmpty){
      logger.fatal("Cannot retrieve auth token\nFinishing...")
      System.exit(1)
    }
    connectionProperties.put("ar_token", res.get("ar_token"))
    connectionProperties.put("ar_user", res.get("ar_user"))
    connectionProperties.put("ar_session", res.get("ar_session"))
    connectionProperties.put("auth-token", s"${properties.getString("general.scraping.auth.token.prefix")} ${connectionProperties.getProperty("ar_token")}")
    logger.debug(s"Initial AUTH-TOKEN: ${connectionProperties.getProperty("auth-token")}")
    HttpManager.setConnectionProperties(connectionProperties)

    //DB access
    val daoDB = DatabaseDAO.getInstance()
    var total_users = 0
    var total_recipes = 0
    try {
      daoDB.connectAndCreate
      daoDB.configure()
      total_users = daoDB.countUsers()
      total_recipes = daoDB.countRecipes()
    } catch {
      case ie : InstantiationException =>
        logger.fatal(ie.getMessage)
      case iae : IllegalAccessException =>
        logger.fatal(iae.getMessage)
      case cnfe : ClassNotFoundException =>
        logger.fatal(cnfe.getMessage)
      case sql : SQLException =>
        logger.fatal(sql.getMessage)
    }

    logger.info(s"Starting with ${total_users} users and ${total_recipes} recipes")

    val inCSVDelimiter = properties.getString("stage4.stage1.input.csv.delimiter")
    val outCSVDelimiter = properties.getString("stage4.stage1.output.csv.delimiter")

    val hostname = Utils.getHostName(properties.getString("general.extraction.default.hostname"))
    val outputDir = Utils.resolvePath(4, 1, hostname)

    val csvUsersName : String = properties.getString("stage4.stage1.output.csv.users.filename")
    val csvRecipesName : String = properties.getString("stage4.stage1.output.csv.recipes.filename")
    val csvIngredientsName : String = properties.getString("stage4.stage1.output.csv.ingredients.filename")
    val csvReviewsName : String = properties.getString("stage4.stage1.output.csv.reviews.filename")
    val csvStepsName : String = properties.getString("stage4.stage1.output.csv.steps.filename")
    val csvNutritionName : String = properties.getString("stage4.stage1.output.csv.nutrition.filename")
    val csvRelationshipUserName : String = properties.getString("stage4.stage1.output.csv.relationship_user.filename")
    val csvRelationshipRecipeName : String = properties.getString("stage4.stage1.output.csv.relationship_recipe.filename")
    val csvRecipesURLsName : String = properties.getString("stage4.stage1.output.csv.recipes_urls.filename")
    val csvUserURLsName : String = properties.getString("stage4.stage1.output.csv.users_urls.filename")

    csvUsers = CSVManager.openCSVWriter(outputDir, csvUsersName, inCSVDelimiter.charAt(0), !fromScratch, "UTF-8")
    csvRecipes = CSVManager.openCSVWriter(outputDir, csvRecipesName, inCSVDelimiter.charAt(0), !fromScratch, "UTF-8")
    csvIngredients = CSVManager.openCSVWriter(outputDir, csvIngredientsName, inCSVDelimiter.charAt(0), !fromScratch, "UTF-8")
    csvReviews = CSVManager.openCSVWriter(outputDir, csvReviewsName, inCSVDelimiter.charAt(0), !fromScratch, "UTF-8")
    csvSteps = CSVManager.openCSVWriter(outputDir, csvStepsName, inCSVDelimiter.charAt(0), !fromScratch, "UTF-8")
    csvNutrition = CSVManager.openCSVWriter(outputDir, csvNutritionName, inCSVDelimiter.charAt(0), !fromScratch, "UTF-8")
    csvRelationshipUser = CSVManager.openCSVWriter(outputDir, csvRelationshipUserName, inCSVDelimiter.charAt(0), !fromScratch, "UTF-8")
    csvRelationshipRecipe = CSVManager.openCSVWriter(outputDir, csvRelationshipRecipeName, inCSVDelimiter.charAt(0), !fromScratch, "UTF-8")
    csvRecipesUrls = CSVManager.openCSVWriter(outputDir, csvRecipesURLsName, inCSVDelimiter.charAt(0), !fromScratch, "UTF-8")
    csvUsersUrls = CSVManager.openCSVWriter(outputDir, csvUserURLsName, inCSVDelimiter.charAt(0), !fromScratch, "UTF-8")

    if(fromScratch) {
      csvUsers.writeRow(Utils.headerToSeq(properties.getString("stage4.stage1.output.csv.users.header"), outCSVDelimiter.charAt(0)))
      csvRecipes.writeRow(Utils.headerToSeq(properties.getString("stage4.stage1.output.csv.recipes.header"), outCSVDelimiter.charAt(0)))
      csvIngredients.writeRow(Utils.headerToSeq(properties.getString("stage4.stage1.output.csv.ingredients.header"), outCSVDelimiter.charAt(0)))
      csvReviews.writeRow(Utils.headerToSeq(properties.getString("stage4.stage1.output.csv.reviews.header"), outCSVDelimiter.charAt(0)))
      csvSteps.writeRow(Utils.headerToSeq(properties.getString("stage4.stage1.output.csv.steps.header"), outCSVDelimiter.charAt(0)))
      csvNutrition.writeRow(Utils.headerToSeq(properties.getString("stage4.stage1.output.csv.nutrition.header"), outCSVDelimiter.charAt(0)))
      csvRelationshipUser.writeRow(Utils.headerToSeq(properties.getString("stage4.stage1.output.csv.relationship_user.header"), outCSVDelimiter.charAt(0)))
      csvRelationshipRecipe.writeRow(Utils.headerToSeq(properties.getString("stage4.stage1.output.csv.relationship_recipe.header"), outCSVDelimiter.charAt(0)))
      csvRecipesUrls.writeRow(Utils.headerToSeq(properties.getString("stage4.stage1.output.csv.recipes_urls.header"), outCSVDelimiter.charAt(0)))
      csvUsersUrls.writeRow(Utils.headerToSeq(properties.getString("stage4.stage1.output.csv.users_urls.header"), outCSVDelimiter.charAt(0)))
    }
    var csvReader : CSVReader = null

    def beforeExit = {
      CSVManager.closeCSVReader(csvReader)
      CSVManager.closeCSVWriter(csvUsers)
      CSVManager.closeCSVWriter(csvRecipes)
      CSVManager.closeCSVWriter(csvIngredients)
      CSVManager.closeCSVWriter(csvReviews)
      CSVManager.closeCSVWriter(csvSteps)
      CSVManager.closeCSVWriter(csvNutrition)
      CSVManager.closeCSVWriter(csvRelationshipUser)
      CSVManager.closeCSVWriter(csvRelationshipRecipe)
      CSVManager.closeCSVWriter(csvRecipesUrls)
      CSVManager.closeCSVWriter(csvUsersUrls)
    }

    val files = Utils.getInputFiles(inputPath)
    val processedFiles = getProcessedFiles(state)

    val delay = properties.getLong("stage4.stage1.scraping.delay.afterLine")
    val nlines = properties.getLong("stage4.stage1.scraping.nlines")
    val delay_nlines = properties.getLong("stage4.stage1.scraping.delay.nlines")

    val max_userreviews = properties.getInt("stage4.stage1.scraping.max.user.reviews")
    val max_userfollowing = properties.getInt("stage4.stage1.scraping.max.user.following")
    val max_userfollower = properties.getInt("stage4.stage1.scraping.max.user.follower")
    val maxrecipes = properties.getInt("stage4.stage1.scraping.max_recipes_user")
    val minrecipes = properties.getInt("stage4.stage1.scraping.minrecipes")
    val minreviews = properties.getInt("stage4.stage1.scraping.minreviews")

    files.foreach(file => {
      if(!processedFiles.contains(file)) {
        var lines = state.getInt("lastLine")
        state.put("currentFile", file.getName)
        csvReader = CSVManager.openCSVReader(file, inCSVDelimiter.charAt(0))
        CSVManager.skipLines(csvReader, lines + 1, (ret) => if(ret.isEmpty)false else true)

        var nreviews = 0
        var lines_processed = 0
        csvReader.foreach(line => {
          val id = line(1).toLong
          logger.info(s"Processing user ${id}")

          var exists = false

          //DB check

          try {
            exists = daoDB.existsUser(id.toString)
          } catch {
            case e@(_: InstantiationException | _: IllegalAccessException | _: ClassNotFoundException | _: SQLException) =>
              logger.fatal(e.getMessage)
              try
                daoDB.disconnect
              catch {
                case e1@(_: SQLException | _: IOException) =>
                  logger.fatal(e1.getMessage)
              }
          }

          if(exists){
            logger.info(s"User ${id} has already been processed")
          }

          else {
            logger.info("Getting user profile...")
            connectionProperties.put("referrer", properties.getProperty("allrecipes.url.base"))
            val potUser = Extractor.extractUser(id, connectionProperties, inCSVDelimiter)
            if (potUser.isEmpty) {
              logger.fatal("Stopping...")
              beforeExit
              System.exit(1)
            }
            val user = potUser.get
            if(!isValidUser(user, minrecipes, minreviews)){
              logger.warn(s"User has less than ${minrecipes} recipes. Discarded.")
            }
            else{

              Thread.sleep(500)

              logger.info("Getting user following...")
              val potFollowing = Extractor.extractFollowing(id, connectionProperties, inCSVDelimiter, max_userfollowing)
              if (potFollowing.isEmpty) {
                logger.fatal("Stopping...")
                beforeExit
                System.exit(1)
              }
              val following = potFollowing.get
              logger.info(s"Got ${following.length} following")

              logger.info("Getting user followers...")
              val potFollowers = Extractor.extractFollowers(id, connectionProperties, inCSVDelimiter, max_userfollower)
              if (potFollowers.isEmpty) {
                logger.fatal("Stopping...")
                beforeExit
                System.exit(1)
              }
              val followers = potFollowers.get
              logger.info(s"Got ${followers.length} followers")
              Thread.sleep(500)

              connectionProperties.replace("referrer", user.profileUrl)

              logger.info("Getting user recipes...")
              val potUserRecipes = Extractor.extractUserRecipes(user, connectionProperties, outCSVDelimiter, maxrecipes)
              if (potUserRecipes.isEmpty) {
                logger.fatal("Impossible to extract user recipes.\nClosing...")
                System.exit(1)
              }

              val user_recipes = potUserRecipes.get
              var new_recipes: Map[String, Seq[Recipe]] = user_recipes._1
              val rep_recipes: Map[String, Seq[Long]] = user_recipes._2

              var newIDs = new_recipes.values.reduce((a, b) => a ++ b).flatMap(r => Seq(r.id)).toSet
              val new_recipes_number = newIDs.size
              var rep_recipes_number = 0
              rep_recipes.values.foreach(rep_recipes_number += _.length)
              logger.info(s"Got ${newIDs.size} new recipes and ${rep_recipes_number} repeated recipes")

              val len = new_recipes_number + rep_recipes_number

              if (len > 0) {
                logger.info("Getting user reviews...")
                val potReviews = Extractor.extractUserReviews(user.id, connectionProperties, outCSVDelimiter, max_userreviews)
                if (potReviews.isEmpty) {
                  logger.fatal("Stopping...")
                  beforeExit
                  System.exit(1)
                }
                val reviews = potReviews.get
                logger.info(s"Got ${reviews.length} reviews")

                logger.info("Getting recipes from reviews...")
                Thread.sleep(500)
                val recipe_reviews = Extractor.extractRecipeFromReviews(user, reviews, newIDs, connectionProperties, outCSVDelimiter)
                val new_recipe_reviews = recipe_reviews._1
                val repeated_recipe_reviews = recipe_reviews._2

                logger.info(s"Got ${new_recipe_reviews.length} new recipes and ${repeated_recipe_reviews.length} repeated recipes")

                newIDs ++= new_recipe_reviews.flatMap(r => Seq(r.id)).toSet

                new_recipes += "review" -> new_recipe_reviews

                val allrecipes: Map[String, Seq[Long]] = Map(
                  "recipes" -> (new_recipes("recipes").map(_.id) ++ rep_recipes("recipes")),
                  "fav" -> (new_recipes("fav").map(_.id) ++ rep_recipes("fav")),
                  "madeit" -> (new_recipes("madeit").map(_.id) ++ rep_recipes("madeit")),
                  "review" -> (new_recipe_reviews.map(_.id) ++ repeated_recipe_reviews)
                )
                rep_recipes.flatMap({case(list, seq) => Seq(1)})

                val following = potFollowing.get.filter(_.id != user.id)
                val followers = potFollowers.get.filter(_.id != user.id)

                printUser(user, csvUsers)
                printReviewList(potReviews.get, (csvReviews))
                printUserFollowing(following, user, (csvUsers, csvRelationshipUser, csvUsersUrls))
                printUserFollowers(followers, user, (csvUsers, csvRelationshipUser, csvUsersUrls))
                printRecipeList(new_recipes, (csvRecipes, csvIngredients, csvSteps, csvNutrition, csvRecipesUrls))
                printUserRecipesList(user.id, allrecipes, csvRelationshipRecipe)

                nreviews += potReviews.get.length
                total_users += 1
                total_recipes += new_recipes_number + rep_recipes_number + new_recipe_reviews.length + repeated_recipe_reviews.length

                logger.info(s"Extracted ${nreviews} reviews")
                logger.info(s"Extracted ${total_recipes} recipes in total")
                logger.info(s"Extracted ${total_users} users in total")

                //DB insertion
                try {
                  daoDB.insertUser(id.toString)
                } catch {
                  case sql : SQLException =>
                    logger.fatal(sql.getMessage)
                }

                newIDs.foreach(id =>
                  //DB insertion
                  try {
                    daoDB.insertRecipe(id.toString)
                  } catch {
                    case sql : SQLException =>
                      logger.fatal(sql.getMessage)
                  }
                )

              }
              else {
                logger.warn("Recipe list is empty")
              }
            }
          }
          lines += 1
          state.put("lastLine", lines)
          logger.info(s"Line $lines processed")
          logger.info(s"${state.toString}")
          if(lines % nlines == 0){
            logger.info(s"$nlines lines processed. Sleeping...")
            Thread.sleep(delay_nlines)
          }
          else{
            logger.info("Sleeping...")
            Thread.sleep(delay)
          }
          logger.info(s"Processed user ${id}")
        })
        CSVManager.closeCSVReader(csvReader)
      }
    })
  }

  /*
  def stage2(inputPath : String, state : JSONObject, fromScratch : Boolean) = {

    connectionProperties = new Properties()
    connectionProperties.put("timeout", properties.getProperty("stage4.stage1.scraping.delay.timeout"))
    connectionProperties.put("max-body-size", properties.getProperty("stage4.stage1.scraping.maxBodySize"))
    connectionProperties.put("follow-redirects", properties.getProperty("stage4.stage1.scraping.followRedirects"))
    connectionProperties.put("api-host", properties.getProperty("allrecipes.api.host"))
    connectionProperties.put("base-host", properties.getProperty("allrecipes.url.base"))
    connectionProperties.put("host", properties.getProperty("allrecipes.host"))
    connectionProperties.put("attempts", properties.getProperty("stage4.stage1.scraping.attempts"))
    connectionProperties.put("delay-detection", properties.getProperty("stage4.stage1.scraping.delay.detection"))
    connectionProperties.put("delay-auth", properties.getProperty("stage4.stage1.scraping.delay.auth"))
    connectionProperties.put("referrer", properties.getProperty("general.scraping.referrer"))
    connectionProperties.put("max-pagesize", properties.getProperty("stage4.stage1.scraping.maxpagesize"))
    connectionProperties.put("delay-recipe", properties.getProperty("stage4.stage1.scraping.delay.recipe"))
    connectionProperties.put("delay-nrecipes", properties.getProperty("stage4.stage1.scraping.delay.nrecipes"))
    connectionProperties.put("nrecipes", properties.getProperty("stage4.stage1.scraping.nrecipes"))
    connectionProperties.put("delay-recipe-list", properties.getProperty("stage4.stage1.scraping.delay.recipe_list"))
    connectionProperties.put("max-times-301", properties.getProperty("general.extraction.http.max.times.301"))
    connectionProperties.put("default-category", properties.getProperty("stage4.stage1.scraping.recipe.category.default"))

    HttpManager.setProperties(properties)
    HttpManager.setConnectionProperties(connectionProperties)
    val res = HttpManager.requestAuthToken()
    if(res.isEmpty){
      logger.fatal("Cannot retrieve auth token\nFinishing...")
      System.exit(1)
    }
    connectionProperties.put("ar_token", res.get("ar_token"))
    connectionProperties.put("ar_user", res.get("ar_user"))
    connectionProperties.put("ar_session", res.get("ar_session"))
    connectionProperties.put("auth-token", s"${properties.getString("general.scraping.auth.token.prefix")} ${connectionProperties.getProperty("ar_token")}")
    logger.debug(s"Initial AUTH-TOKEN: ${connectionProperties.getProperty("auth-token")}")
    HttpManager.setConnectionProperties(connectionProperties)

    //DB access
    val daoDB = DatabaseDAO.getInstance()
    var total_users = 0
    var total_recipes = 0
    try {
      daoDB.connectAndCreate
      daoDB.configure()
      total_users = daoDB.countUsers()
      total_recipes = daoDB.countRecipes()
    } catch {
      case ie : InstantiationException =>
        logger.fatal(ie.getMessage)
      case iae : IllegalAccessException =>
        logger.fatal(iae.getMessage)
      case cnfe : ClassNotFoundException =>
        logger.fatal(cnfe.getMessage)
      case sql : SQLException =>
        logger.fatal(sql.getMessage)
    }

    logger.info(s"Starting with ${total_users} users and ${total_recipes} recipes")

    val inCSVDelimiter = properties.getString("stage4.stage1.input.csv.delimiter")
    val outCSVDelimiter = properties.getString("stage4.stage1.output.csv.delimiter")

    val hostname = Utils.getHostName(properties.getString("general.extraction.default.hostname"))
    val outputDir = Utils.resolvePath(4, 1, hostname)

    val csvUsersName : String = properties.getString("stage4.stage1.output.csv.users.filename")
    val csvRecipesName : String = properties.getString("stage4.stage1.output.csv.recipes.filename")
    val csvIngredientsName : String = properties.getString("stage4.stage1.output.csv.ingredients.filename")
    val csvReviewsName : String = properties.getString("stage4.stage1.output.csv.reviews.filename")
    val csvStepsName : String = properties.getString("stage4.stage1.output.csv.steps.filename")
    val csvNutritionName : String = properties.getString("stage4.stage1.output.csv.nutrition.filename")
    val csvRelationshipUserName : String = properties.getString("stage4.stage1.output.csv.relationship_user.filename")
    val csvRelationshipRecipeName : String = properties.getString("stage4.stage1.output.csv.relationship_recipe.filename")
    val csvRecipesURLsName : String = properties.getString("stage4.stage1.output.csv.recipes_urls.filename")
    val csvUserURLsName : String = properties.getString("stage4.stage1.output.csv.users_urls.filename")

    csvUsers = CSVManager.openCSVWriter(outputDir, csvUsersName, inCSVDelimiter.charAt(0), !fromScratch, "UTF-8")
    csvRecipes = CSVManager.openCSVWriter(outputDir, csvRecipesName, inCSVDelimiter.charAt(0), !fromScratch, "UTF-8")
    csvIngredients = CSVManager.openCSVWriter(outputDir, csvIngredientsName, inCSVDelimiter.charAt(0), !fromScratch, "UTF-8")
    csvReviews = CSVManager.openCSVWriter(outputDir, csvReviewsName, inCSVDelimiter.charAt(0), !fromScratch, "UTF-8")
    csvSteps = CSVManager.openCSVWriter(outputDir, csvStepsName, inCSVDelimiter.charAt(0), !fromScratch, "UTF-8")
    csvNutrition = CSVManager.openCSVWriter(outputDir, csvNutritionName, inCSVDelimiter.charAt(0), !fromScratch, "UTF-8")
    csvRelationshipUser = CSVManager.openCSVWriter(outputDir, csvRelationshipUserName, inCSVDelimiter.charAt(0), !fromScratch, "UTF-8")
    csvRelationshipRecipe = CSVManager.openCSVWriter(outputDir, csvRelationshipRecipeName, inCSVDelimiter.charAt(0), !fromScratch, "UTF-8")
    csvRecipesUrls = CSVManager.openCSVWriter(outputDir, csvRecipesURLsName, inCSVDelimiter.charAt(0), !fromScratch, "UTF-8")
    csvUsersUrls = CSVManager.openCSVWriter(outputDir, csvUserURLsName, inCSVDelimiter.charAt(0), !fromScratch, "UTF-8")

    if(fromScratch) {
      csvUsers.writeRow(Utils.headerToSeq(properties.getString("stage4.stage1.output.csv.users.header"), outCSVDelimiter.charAt(0)))
      csvRecipes.writeRow(Utils.headerToSeq(properties.getString("stage4.stage1.output.csv.recipes.header"), outCSVDelimiter.charAt(0)))
      csvIngredients.writeRow(Utils.headerToSeq(properties.getString("stage4.stage1.output.csv.ingredients.header"), outCSVDelimiter.charAt(0)))
      csvReviews.writeRow(Utils.headerToSeq(properties.getString("stage4.stage1.output.csv.reviews.header"), outCSVDelimiter.charAt(0)))
      csvSteps.writeRow(Utils.headerToSeq(properties.getString("stage4.stage1.output.csv.steps.header"), outCSVDelimiter.charAt(0)))
      csvNutrition.writeRow(Utils.headerToSeq(properties.getString("stage4.stage1.output.csv.nutrition.header"), outCSVDelimiter.charAt(0)))
      csvRelationshipUser.writeRow(Utils.headerToSeq(properties.getString("stage4.stage1.output.csv.relationship_user.header"), outCSVDelimiter.charAt(0)))
      csvRelationshipRecipe.writeRow(Utils.headerToSeq(properties.getString("stage4.stage1.output.csv.relationship_recipe.header"), outCSVDelimiter.charAt(0)))
      csvRecipesUrls.writeRow(Utils.headerToSeq(properties.getString("stage4.stage1.output.csv.recipes_urls.header"), outCSVDelimiter.charAt(0)))
      csvUsersUrls.writeRow(Utils.headerToSeq(properties.getString("stage4.stage1.output.csv.users_urls.header"), outCSVDelimiter.charAt(0)))
    }
    var csvReader : CSVReader = null

    def beforeExit = {
      CSVManager.closeCSVReader(csvReader)
      CSVManager.closeCSVWriter(csvUsers)
      CSVManager.closeCSVWriter(csvRecipes)
      CSVManager.closeCSVWriter(csvIngredients)
      CSVManager.closeCSVWriter(csvReviews)
      CSVManager.closeCSVWriter(csvSteps)
      CSVManager.closeCSVWriter(csvNutrition)
      CSVManager.closeCSVWriter(csvRelationshipUser)
      CSVManager.closeCSVWriter(csvRelationshipRecipe)
      CSVManager.closeCSVWriter(csvRecipesUrls)
      CSVManager.closeCSVWriter(csvUsersUrls)
    }

    val files = Utils.getInputFiles(inputPath)
    val processedFiles = getProcessedFiles(state)

    val delay = properties.getLong("stage4.stage1.scraping.delay.afterLine")
    val nlines = properties.getLong("stage4.stage1.scraping.nlines")
    val delay_nlines = properties.getLong("stage4.stage1.scraping.delay.nlines")

    val max_userreviews = properties.getInt("stage4.stage1.scraping.max.user.reviews")
    val max_userfollowing = properties.getInt("stage4.stage1.scraping.max.user.following")
    val max_userfollower = properties.getInt("stage4.stage1.scraping.max.user.follower")
    val maxrecipes = properties.getInt("stage4.stage1.scraping.max_recipes_user")
    val minrecipes = properties.getInt("stage4.stage1.scraping.minrecipes")
    val minreviews = properties.getInt("stage4.stage1.scraping.minreviews")

    files.foreach(file => {
      if(!processedFiles.contains(file)) {
        var lines = state.getInt("lastLine")
        state.put("currentFile", file.getName)
        csvReader = CSVManager.openCSVReader(file, inCSVDelimiter.charAt(0))
        CSVManager.skipLines(csvReader, lines + 1, (ret) => if(ret.isEmpty)false else true)

        var nreviews = 0
        var lines_processed = 0
        csvReader.foreach(line => {
          val id = line(1).toLong
          logger.info(s"Processing user ${id}")

          var exists = false

          //DB check

          try {
            exists = daoDB.existsUser(id.toString)
          } catch {
            case e@(_: InstantiationException | _: IllegalAccessException | _: ClassNotFoundException | _: SQLException) =>
              logger.fatal(e.getMessage)
              try
                daoDB.disconnect
              catch {
                case e1@(_: SQLException | _: IOException) =>
                  logger.fatal(e1.getMessage)
              }
          }

          if(exists){
            logger.info(s"User ${id} has already been processed")
          }

          else {
            logger.info("Getting user profile...")
            connectionProperties.put("referrer", properties.getProperty("allrecipes.url.base"))
            val potUser = Extractor.extractUser(id, connectionProperties, inCSVDelimiter)
            if (potUser.isEmpty) {
              logger.fatal("Stopping...")
              beforeExit
              System.exit(1)
            }
            val user = potUser.get
            if(!isValidUser(user, minrecipes, minreviews)){
              logger.warn(s"User has less than ${minrecipes} recipes. Discarded.")
            }
            else{
              Thread.sleep(500)

              logger.info("Getting user following...")
              val potFollowing = Extractor.extractFollowing(id, connectionProperties, inCSVDelimiter, max_userfollowing)
              if (potFollowing.isEmpty) {
                logger.fatal("Stopping...")
                beforeExit
                System.exit(1)
              }
              val following = potFollowing.get
              logger.info(s"Got ${following.length} following")

              logger.info("Getting user followers...")
              val potFollowers = Extractor.extractFollowers(id, connectionProperties, inCSVDelimiter, max_userfollower)
              if (potFollowers.isEmpty) {
                logger.fatal("Stopping...")
                beforeExit
                System.exit(1)
              }
              val followers = potFollowers.get
              logger.info(s"Got ${followers.length} followers")
              Thread.sleep(500)

              connectionProperties.replace("referrer", user.profileUrl)

              logger.info("Getting user recipes...")
              val potUserRecipes = Extractor.extractUserRecipes(user, connectionProperties, outCSVDelimiter, maxrecipes)
              if (potUserRecipes.isEmpty) {
                logger.fatal("Impossible to extract user recipes.\nClosing...")
                System.exit(1)
              }

              var new_recipes = 0
              potUserRecipes.get._1.values.foreach(new_recipes += _.length)
              var rep_recipes = 0
              potUserRecipes.get._2.values.foreach(rep_recipes += _.length)
              logger.info(s"Got ${new_recipes} recipes")

              val len = new_recipes + rep_recipes

              if (len > 0) {
                logger.info("Getting user reviews...")
                val potReviews = Extractor.extractUserReviews(user.id, connectionProperties, inCSVDelimiter, max_userreviews)
                if (potReviews.isEmpty) {
                  logger.fatal("Stopping...")
                  beforeExit
                  System.exit(1)
                }
                val reviews = potReviews.get
                logger.info(s"Got ${reviews.length} reviews")

                logger.info("Getting recipes from reviews...")
                //val potRevRecipes = Extractor.extractRecipesFromReviews()

                val following = potFollowing.get.filter(_.id != user.id)
                val followers = potFollowers.get.filter(_.id != user.id)

                printUser(user, csvUsers)
                printReviewList(potReviews.get, (csvReviews))
                printUserFollowing(following, user, (csvUsers, csvRelationshipUser, csvUsersUrls))
                printUserFollowers(followers, user, (csvUsers, csvRelationshipUser, csvUsersUrls))
                //printRecipeList(potUserRecipes.get._1, (csvRecipes, csvIngredients, csvSteps, csvNutrition, csvRecipesUrls))
                printUserRecipesList(user.id, potUserRecipes.get, csvRelationshipRecipe)

                try {
                  total_recipes = daoDB.countRecipes()
                } catch {
                  case ie : InstantiationException =>
                    logger.fatal(ie.getMessage)
                  case iae : IllegalAccessException =>
                    logger.fatal(iae.getMessage)
                  case cnfe : ClassNotFoundException =>
                    logger.fatal(cnfe.getMessage)
                  case sql : SQLException =>
                    logger.fatal(sql.getMessage)
                }

                nreviews += potReviews.get.length
                total_users += 1
                total_recipes += new_recipes

                logger.info(s"Extracted ${nreviews} reviews")
                logger.info(s"Extracted ${total_recipes} recipes in total")
                logger.info(s"Extracted ${total_users} users in total")

                //DB insertion
                try {
                  daoDB.insertUser(id.toString)
                } catch {
                  case sql : SQLException =>
                    logger.fatal(sql.getMessage)
                }

                potUserRecipes.get._1.foreach({case(_, recipes) =>
                  recipes.foreach(recipe =>
                    //DB insertion
                    try {
                      daoDB.insertRecipe(recipe.id.toString)
                    } catch {
                      case sql : SQLException =>
                        logger.fatal(sql.getMessage)
                    }
                  )
                })

              }
              else {
                logger.warn("Recipe list is empty")
              }
            }
          }
          lines += 1
          state.put("lastLine", lines)
          logger.info(s"Line $lines processed")
          logger.info(s"${state.toString}")
          if(lines % nlines == 0){
            logger.info(s"$nlines lines processed. Sleeping...")
            Thread.sleep(delay_nlines)
          }
          else{
            logger.info("Sleeping...")
            Thread.sleep(delay)
          }
          logger.info(s"Processed user ${id}")
        })
        CSVManager.closeCSVReader(csvReader)
      }
    })
  }
  */

  def printUser(user: User, writer: CSVWriter) : Unit = {
    writer.writeRow(user.toSeq)
  }

  def printUserFollowing(users: Seq[User], related_user: User, writers: (CSVWriter, CSVWriter, CSVWriter)) : Unit = {
    users foreach { user =>
      printUser(user, writers._1)
      writers._2.writeRow(Utils.flatten(Seq(related_user.id, user.id)))
      writers._3.writeRow(Utils.flatten(Seq(user.profileUrl, user.id)))
    }
  }

  def printUserRecipesList(id: Long, recipes: Map[String, Seq[Long]], writer : CSVWriter) : Unit = {
    recipes.foreach({case(list, recipeList) => {
      recipeList foreach {recipe =>
        writer.writeRow(Utils.flatten(Seq(list, recipe, id)))
      }
    }})
  }

  def printUserFollowers(users: Seq[User], related_user: User, writers: (CSVWriter, CSVWriter, CSVWriter)) : Unit = {
    users foreach { user =>
      printUser(user, writers._1)
      writers._2.writeRow(Utils.flatten(Seq(user.id, related_user.id)))
      writers._3.writeRow(Utils.flatten(Seq(user.profileUrl, user.id)))
    }
  }

  def printReviewList(reviews: Seq[Review], writer: CSVWriter) : Unit = {
    reviews.foreach(review => {
      writer.writeRow(review.toSeq())
    })
  }

  def printRecipeList(recipes: Map[String, Seq[Recipe]], writers: (CSVWriter, CSVWriter, CSVWriter, CSVWriter, CSVWriter)) : Unit = {

    recipes.foreach({case(list, recipeList) => {
      printRecipeList(recipeList, list, writers)
    }
    })
  }

  def printRecipeList(recipes: Seq[Recipe], list: String, writers: (CSVWriter, CSVWriter, CSVWriter, CSVWriter, CSVWriter)) = {
    recipes foreach {recipe =>
      writers._1.writeRow(Utils.flatten(Seq(list, recipe.toSeq())))
      recipe.ingredients.foreach(ingredient => writers._2.writeRow(Utils.flatten(Seq(recipe.id, ingredient.toSeq()))))
      recipe.steps.foreach({ case (number, text) => writers._3.writeRow(Seq(recipe.id, number, text)) })
      if (recipe.nutritionInfo.isDefined)
        writers._4.writeRow(Utils.flatten(Seq(recipe.id, recipe.nutritionInfo.get.toSeq())))
      writers._5.writeRow(Seq(recipe.id, recipe.weburl))
    }
  }

  def isValidUser(user: User, threshold_recipes: Int, threshold_reviews: Int) : Boolean = {
    (user.recipeCount + user.favCount + user.madeitCount) >= threshold_recipes && user.reviewCount >= threshold_reviews
  }
}