package com.walkmind.tricks

import java.util.concurrent.TimeUnit
import javax.naming.directory.{BasicAttribute, InitialDirContext}

import com.google.common.cache.{CacheBuilder, CacheLoader}
import com.google.common.net.InternetDomainName
import org.apache.commons.validator.routines.EmailValidator
import org.apache.james.mime4j.field.address.{DefaultAddressParser, ParseException}

import scala.util.Try

object EmailTools {

  private val emailValidator = EmailValidator.getInstance()

  // First item in tuple is character after which everything can be discarded, second items indicates whether dots can be removed from email or not
  private val domainsSpecificSettings = Map(
    // Gmail and Google Apps
    "google.com" ->('+', true),
    "googlemail.com" ->('+', true),

    // icloud.com, apple.com, mac.com and me.com
    "icloud.com" ->('+', false),
    "apple.com" ->('+', false),

    // Used by outlook.com, hotmail.com and live.com
    "hotmail.com" ->('+', false),
    "outlook.com" ->('+', false),

    // controlled by fastmail.com clients
    "messagingengine.com" ->('+', false),

    // controlled by runbox.com clients
    "runbox.com" ->('+', false),

    // all possible yahoo regional domains
    "yahoo.co.jp" ->('-', false),
    "yahoodns.net" ->('-', false)
  )

  private val mxRecordsCache = CacheBuilder.newBuilder()
    .maximumSize(10000)
    .expireAfterWrite(1, TimeUnit.DAYS)
    .build(
      new CacheLoader[String, Option[Seq[String]]]() {
        override def load(domainName: String): Option[Seq[String]] = lookupMailHosts(domainName)
      })

  // Returns None for invalid domains or sequence of MX records for domain (can be empty) sorted from most preferred to least preferred
  private def lookupMailHosts(domainName: String): Option[Seq[String]] = {
    // see: RFC 974 - Mail routing and the domain system
    // see: RFC 1034 - Domain names - concepts and facilities
    // see: http://java.sun.com/j2se/1.5.0/docs/guide/jndi/jndi-dns.html
    //    - DNS Service Provider for the Java Naming Directory Interface (JNDI)

    // get the default initial Directory Context
    val iDirC = new InitialDirContext()
    // get the MX records from the default DNS directory service provider
    //    NamingException thrown if no DNS record found for domainName

    Try {
      val attributes = iDirC.getAttributes("dns:/" + domainName, Array("MX"))
      // attributeMX is an attribute ('list') of the Mail Exchange(MX) Resource Records(RR)
      val attributeMX = attributes.get("MX").asInstanceOf[BasicAttribute]

      // Sort by RR and remove trailing dot
      if ((attributeMX != null) && (attributeMX.size() != 0)) {
        val items = for (i <- 0 until attributeMX.size()) yield {
          val pair = attributeMX.get(i).toString.split("\\s+")
          (pair(0).toInt, pair(1).toLowerCase)
        }
        items.sortBy { case (rr, _) => rr }.map { case (_, v) => if (v.endsWith(".")) v.take(v.length - 1) else v }
      } else
        Nil
    }.toOption
  }

  def isValid(email: String): Boolean =
    if (emailValidator.isValid(email))
      canonicalize(email).isDefined
    else
      false

  def canonicalize(email: String, enableRemoveTag: Boolean = true, enableCleanLocalPart: Boolean = true): Option[String] = {

    try {
      // RFC 5322 states that the local-part of the email (before the @) is case sensitive, but the de facto standard for virtually all providers is to ignore case
      val mailbox = DefaultAddressParser.DEFAULT.parseMailbox(email.toLowerCase)

      mxRecordsCache.get(mailbox.getDomain) match {
        case Some(mxRecords) =>

          try {
            val mxTopDomains = mxRecords.map(v => InternetDomainName.from(v).topPrivateDomain().toString).distinct

            val cleanLocalPart =
              for (domainName <- mxTopDomains.find(domainsSpecificSettings.contains); (tagSeparator, removeDots) <- domainsSpecificSettings.get(domainName)) yield {

              val pos = mailbox.getLocalPart.indexOf(tagSeparator)
              val localPartNoTags =
                if (enableRemoveTag && (pos >= 0))
                  mailbox.getLocalPart.take(pos)
                else
                  mailbox.getLocalPart

              if (enableCleanLocalPart && removeDots)
                localPartNoTags.replace(".", "")
              else
                localPartNoTags
            }

            if (cleanLocalPart.isDefined)
              cleanLocalPart.map(v => s"$v@${mailbox.getDomain}")
            else
              Option(mailbox.getAddress)
          } catch {
            case _ : IllegalArgumentException =>
//              logger.warn(s"Couldn't canonicalize `$email` due to: ${ex.getMessage}")
              None
            case _ : Throwable =>
//              logger.error(s"Couldn't canonicalize `$email` due to: ${ex.getMessage}")
              None
          }
        // There is no MX DNS record for domain part of the email thus email is probably fake
        case None =>
          None
      }
    } catch {
      case ex: ParseException =>
//        logger.debug(s"Can't parse `$email` as email", ex)
        None
    }
  }

}
