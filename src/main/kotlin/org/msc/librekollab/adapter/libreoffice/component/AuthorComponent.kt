package org.msc.librekollab.adapter.libreoffice.component

import com.sun.star.beans.PropertyValue
import com.sun.star.beans.XPropertySet
import com.sun.star.lang.XMultiServiceFactory
import com.sun.star.uno.UnoRuntime
import com.sun.star.uno.XComponentContext
import com.sun.star.util.XChangesBatch
import org.msc.librekollab.domain.KollabAPI

class AuthorComponent(private val componentContext: XComponentContext) {

    companion object {
        private const val CONFIG_PROVIDER_SERVICE = "com.sun.star.configuration.ConfigurationProvider"
        private const val CONFIG_UPDATE_ACCESS_SERVICE = "com.sun.star.configuration.ConfigurationUpdateAccess"
        private const val NODEPATH_PROPERTY_NAME = "nodepath"
        private const val USER_PROFILE_NODE_PATH = "/org.openoffice.UserProfile/Data"
        private const val GIVEN_NAME_PROPERTY = "givenname"
        private const val SURNAME_PROPERTY = "sn"
    }

    fun withAuthor(author: String, block: () -> Unit) {
        if (author == KollabAPI.UNKNOWN_AUTHOR) {
            block()
            return
        }
        val access = userProfileAccess()
        val oldFirst = access.getPropertyValue(GIVEN_NAME_PROPERTY) as String
        val oldLast = access.getPropertyValue(SURNAME_PROPERTY) as String
        try {
            setProfileName(access, author, "")
            block()
        } finally {
            setProfileName(access, oldFirst, oldLast)
        }
    }

    private fun userProfileAccess(): XPropertySet {
        val configProvider = UnoRuntime.queryInterface(
            XMultiServiceFactory::class.java,
            componentContext.serviceManager.createInstanceWithContext(CONFIG_PROVIDER_SERVICE, componentContext)
        )
        val nodeArg = PropertyValue().apply { Name = NODEPATH_PROPERTY_NAME; Value = USER_PROFILE_NODE_PATH }
        return UnoRuntime.queryInterface(
            XPropertySet::class.java,
            configProvider.createInstanceWithArguments(CONFIG_UPDATE_ACCESS_SERVICE, arrayOf(nodeArg))
        )
    }

    private fun setProfileName(access: XPropertySet, givenName: String, surname: String) {
        access.setPropertyValue(GIVEN_NAME_PROPERTY, givenName)
        access.setPropertyValue(SURNAME_PROPERTY, surname)
        UnoRuntime.queryInterface(XChangesBatch::class.java, access).commitChanges()
    }
}
