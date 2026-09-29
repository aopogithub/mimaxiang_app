package com.example.vaultbox.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AutofillMatcherTest {
    private val target = AutofillTarget("https://www.example.com/login", "com.example.app, com.example.other")

    @Test fun normalizesWebsiteUrls() {
        for (website in listOf("example.com", " HTTPS://WWW.EXAMPLE.COM:443/login?q=1#login ",
            "//www.example.com/path", "example.com:8443/login", "example.com.")) {
            assertEquals(website, "example.com", AutofillMatcher.normalizeHost(website))
        }
    }

    @Test fun matchesOnlyTheSameWebsite() {
        assertTrue(AutofillMatcher.matches(target, "com.android.chrome", "www.example.com"))
        for (domain in listOf("other.com", "evil-example.com", "example.com.evil.test", "login.example.com", "com")) {
            assertFalse(domain, AutofillMatcher.matches(target, "com.example.app", domain))
        }
        assertFalse(AutofillMatcher.matches(AutofillTarget("login.example.com", ""), null, "example.com"))
    }

    @Test fun matchesMultipleExplicitAppAssociations() {
        assertTrue(AutofillMatcher.matches(target, "com.example.app", null))
        assertTrue(AutofillMatcher.matches(target, "com.example.other", ""))
        assertTrue(AutofillMatcher.matches(target, " com.example.app ", null))
    }

    @Test fun rejectsPartialOrDifferentlyCasedPackages() {
        for (pkg in listOf("com.example", "com.example.app.fake", "COM.EXAMPLE.APP")) {
            assertFalse(pkg, AutofillMatcher.matches(target, pkg, null))
        }
    }

    @Test fun websiteOrTitleTextCannotSubstituteForAnAppAssociation() {
        assertFalse(AutofillMatcher.matches(AutofillTarget("com.example.app", ""), "com.example.app", null))
    }

    @Test fun webPagesNeverFallBackToAppAssociations() {
        assertFalse(AutofillMatcher.matches(target, "com.example.app", "other.com"))
        assertFalse(AutofillMatcher.matches(target, "com.example.app", "not a valid host"))
        assertTrue(AutofillMatcher.matches(target, "com.example.unrelated", "example.com"))
    }

    @Test fun browsersWithoutADomainDoNotMatchByPackage() {
        for (pkg in listOf("com.android.chrome", "org.mozilla.firefox", "com.microsoft.emmx", "com.vendor.browser")) {
            assertFalse(pkg, AutofillMatcher.matches(AutofillTarget("", pkg), pkg, null))
        }
    }

    @Test fun missingTargetOrAssociationDoesNotMatch() {
        assertFalse(AutofillMatcher.matches(target, null, null))
        assertFalse(AutofillMatcher.matches(target, "", ""))
        assertFalse(AutofillMatcher.matches(AutofillTarget("", ""), "com.example.app", "example.com"))
    }

    @Test fun rejectsInvalidOrAmbiguousUrls() {
        for (url in listOf("", " ", "https://", "https://user@example.com", "https://example.com@evil.com",
            "javascript:example.com", "ftp://example.com", "https://exa mple.com", "https://%65xample.com",
            "https://example.com\\@evil.com", "https://example.com:99999", "https://example.com:-1")) {
            assertNull(url, AutofillMatcher.normalizeHost(url))
        }
        assertNull(AutofillMatcher.normalizeHost(null))
    }

    @Test fun internationalDomainsMatchTheirPunycodeForm() {
        assertEquals("xn--bcher-kva.example", AutofillMatcher.normalizeHost("https://bücher.example/login"))
        assertTrue(AutofillMatcher.matches(AutofillTarget("bücher.example", ""), null, "xn--bcher-kva.example"))
    }

    @Test fun hostNormalizationIsIndependentOfDeviceLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("i.example", AutofillMatcher.normalizeHost("HTTPS://WWW.I.EXAMPLE"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun numericHostsRequireExactMatches() {
        assertTrue(AutofillMatcher.matches(AutofillTarget("http://192.168.1.1:8080", ""), null, "192.168.1.1"))
        assertFalse(AutofillMatcher.matches(AutofillTarget("192.168.1.1", ""), null, "168.1.1"))
        assertEquals("[::1]", AutofillMatcher.normalizeHost("http://[::1]:8080"))
    }
}
