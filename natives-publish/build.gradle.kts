import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar

plugins {
    java
    alias(libs.plugins.maven.publish.base)
}

mavenPublishing {
    configure(JavaLibrary(JavadocJar.Javadoc()))
}
