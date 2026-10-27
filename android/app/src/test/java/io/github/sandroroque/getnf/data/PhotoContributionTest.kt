package io.github.sandroroque.getnf.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the app counts a receipt as one it failed to read (D103), so its photos may go up if the user allowed it. */
class PhotoContributionTest {
  private val prorated = listOf("prorated", "prorated", "prorated")

  @Test fun aDiscountNoPhotoExplainedIsAFailure() = assertTrue(PhotoContribution.isFailure(56, 5, prorated))

  @Test fun aDiscountReadFromThePhotosIsNot() = assertFalse(PhotoContribution.isFailure(56, 5, listOf("prorated", "photo", "prorated")))

  @Test fun aDiscountSetByHandIsNot() = assertFalse(PhotoContribution.isFailure(56, 5, listOf("prorated", "manual")))

  @Test fun noDiscountOrNoPhotosOrNoLinesIsNot() {
    assertFalse(PhotoContribution.isFailure(0, 5, prorated))
    assertFalse(PhotoContribution.isFailure(56, 0, prorated))
    assertFalse(PhotoContribution.isFailure(56, 5, emptyList()))
    assertFalse(PhotoContribution.isFailure(56, 5, listOf("html", "html")))
  }
}
