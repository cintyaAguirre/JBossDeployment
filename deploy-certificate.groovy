#!/usr/bin/env groovy
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Grab(group='org.wildfly.core', module='wildfly-embedded', version='2.2.1.Final')
@Grab(group='org.wildfly.security', module='wildfly-security-manager', version='1.1.2.Final')
@Grab(group='org.wildfly.core', module='wildfly-cli', version='3.0.0.Beta23')
import org.jboss.as.cli.scriptsupport.*

@Grab(group='org.springframework.retry', module='spring-retry', version='1.2.0.RELEASE')
import org.springframework.retry.*
import org.springframework.retry.support.*
import org.springframework.retry.backoff.*
import org.springframework.retry.policy.*

@Grab(group='commons-io', module='commons-io', version='2.5')
import org.apache.commons.io.*

@Grab(group='com.google.guava', module='guava', version='22.0')
import com.google.common.base.Splitter
import com.google.common.collect.ImmutableList

@Grab(group='org.apache.commons', module='commons-collections4', version='4.0')
import org.apache.commons.collections4.CollectionUtils

final DEFAULT_HOST = "localhost"
final DEFAULT_PORT = "9990"
final OCTOPUS_SSL_REALM = "octopus-ssl-realm"

/*
    Define and parse the command line arguments
 */
def cli = new CliBuilder()

cli.with {
    h longOpt: 'help', 'Show usage information'
    c longOpt: 'controller', args: 1, argName: 'controller', 'WildFly controller'
    d longOpt: 'port', args: 1, argName: 'port', type: Number.class, 'Wildfly management port'
    u longOpt: 'user', args: 1, argName: 'username', required: true, 'WildFly management username'
    p longOpt: 'password', args: 1, argName: 'password', required: true, 'WildFly management password'
    k longOpt: 'keystore-file', args: 1, argName: 'path to keystore', required: true, 'Java keystore file'
    q longOpt: 'keystore-password', args: 1, argName: 'application name', required: true, 'Keystore password'
    s longOpt: 'server-group', args: 1, argName: 'server group', 'Server group to enable in'
}

def options = cli.parse(args)
if (!options) {
    return
}

if (options.h) {
    cli.usage()
    return
}

/*
   Build up a retry template
 */
def retryTemplate = new RetryTemplate()
def retryPolicy = new SimpleRetryPolicy(5)
retryTemplate.setRetryPolicy(retryPolicy)

def backOffPolicy = new ExponentialBackOffPolicy()
backOffPolicy.setInitialInterval(1000L)
retryTemplate.setBackOffPolicy(backOffPolicy)

/*
    Connect to the server
 */
def jbossCli = CLI.newInstance()

retryTemplate.execute(new RetryCallback<Void, Exception>() {
    @Override
    Void doWithRetry(RetryContext context) throws Exception {

        println("Attempt ${context.retryCount + 1} to connect.")

        jbossCli.connect(
                options.controller ?: DEFAULT_HOST,
                Integer.parseInt(options.port ?: DEFAULT_PORT),
                options.user,
                options.password.toCharArray())
        return null
    }
})

/*
    Backup the configuration
 */
retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
    @Override
    CLI.Result doWithRetry(RetryContext context) throws Exception {
        println("Attempt ${context.retryCount + 1} to snapshot the configuration.")

        def snapshotResult = jbossCli.cmd("/:take-snapshot")
        if (!snapshotResult.success) {
            throw new Exception("Failed to snapshot the configuration. ${snapshotResult.response.toString()}")
        }
    }
})

/*
    Find the configuration directory and copy the keystore into it
 */
retryTemplate.execute(new RetryCallback<Void, Exception>() {
    @Override
    Void doWithRetry(RetryContext context) throws Exception {
        println("Attempt ${context.retryCount + 1} to copy keystore to config dir.")

        def configResult = jbossCli.cmd("/core-service=platform-mbean/type=runtime:read-attribute(name=system-properties):read-resource")
        if (!configResult.success) {
            throw new Exception("Failed to read configuration. ${configResult.response.toString()}")
        }

        def configDir = configResult.response
                .get("result")
                .get("system-properties")
                .get("jboss.server.config.dir").asString()

        Files.copy(
                new File(options.'keystore-file').toPath(),
                new File(configDir + File.separator + FilenameUtils.getName(options.'keystore-file')).toPath(),
                StandardCopyOption.REPLACE_EXISTING)

        return null
    }
})

if (jbossCli.getCommandContext().isDomainMode()) {

} else {
    /*
        Also add certificate to admin interface.

        Check for missing private key.
     */

    retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
        @Override
        CLI.Result doWithRetry(RetryContext context) throws Exception {
            println("Attempt ${context.retryCount + 1} to add security realm.")

            def existsResult = jbossCli.cmd("/core-service=management/security-realm=${OCTOPUS_SSL_REALM}:read-resource")
            if (!existsResult.success) {
                def addResult = jbossCli.cmd("/core-service=management/security-realm=${OCTOPUS_SSL_REALM}:add()")
                if (!addResult.success) {
                    throw new Exception("Failed to create security realm. ${addResult.response.toString()}")
                }
            }
        }
    })

    /*
        Add the server identity to the management interface
     */
    retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
        @Override
        CLI.Result doWithRetry(RetryContext context) throws Exception {
            println("Attempt ${context.retryCount + 1} to add server identity.")

            def existsResult = jbossCli.cmd("/core-service=management/security-realm=ManagementRealm/server-identity=ssl:read-resource")
            if (existsResult.success) {
                def removeResult = jbossCli.cmd("/core-service=management/security-realm=ManagementRealm/server-identity=ssl:remove")
                if (!removeResult.success) {
                    throw new Exception("Failed to remove management server identity. ${removeResult.response.toString()}")
                }
            }

            def keystoreFile = FilenameUtils.getName(options.'keystore-file')
                    .replaceAll('\\\\', '\\\\\\\\')
                    .replaceAll('"', '\"')
            def keystorePassword = options.'keystore-password'
                    .replaceAll('\\\\', '\\\\\\\\')
                    .replaceAll('"', '\"')

            def command = "/core-service=management/security-realm=ManagementRealm/server-identity=ssl/:add(" +
                    "alias=\"octopus\", " +
                    "keystore-relative-to=\"jboss.server.config.dir\", " +
                    "keystore-path=\"${keystoreFile}\", " +
                    "keystore-password=\"${keystorePassword}\")"

            def addResult = jbossCli.cmd(command)
            if (!addResult.success) {
                throw new Exception("Failed to create management server identity. ${addResult.response.toString()}")
            }
        }
    })

    /*
        Bind the management interface to the ssl port
     */
    retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
        @Override
        CLI.Result doWithRetry(RetryContext context) throws Exception {
            println("Attempt ${context.retryCount + 1} to add server identity.")

            def socketBindingResult = jbossCli.cmd("/core-service=management/management-interface=http-interface:add(socket-binding=management-https")
            if (socketBindingResult.success) {
                throw new Exception("Failed to change management socket binding. ${socketBindingResult.response.toString()}")
            }
        }
    })

    /*
        Add the server identity to the web interface
     */
    retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
        @Override
        CLI.Result doWithRetry(RetryContext context) throws Exception {
            println("Attempt ${context.retryCount + 1} to add server identity.")

            def existsResult = jbossCli.cmd("/core-service=management/security-realm=${OCTOPUS_SSL_REALM}/server-identity=ssl:read-resource")
            if (existsResult.success) {
                def removeResult = jbossCli.cmd("/core-service=management/security-realm=${OCTOPUS_SSL_REALM}/server-identity=ssl:remove")
                if (!removeResult.success) {
                    throw new Exception("Failed to remove server identity. ${removeResult.response.toString()}")
                }
            }

            def keystoreFile = FilenameUtils.getName(options.'keystore-file')
                    .replaceAll('\\\\', '\\\\\\\\')
                    .replaceAll('"', '\"')
            def keystorePassword = options.'keystore-password'
                    .replaceAll('\\\\', '\\\\\\\\')
                    .replaceAll('"', '\"')

            def command = "/core-service=management/security-realm=${OCTOPUS_SSL_REALM}/server-identity=ssl/:add(" +
                    "alias=\"octopus\", " +
                    "keystore-relative-to=\"jboss.server.config.dir\", " +
                    "keystore-path=\"${keystoreFile}\", " +
                    "keystore-password=\"${keystorePassword}\")"

            def addResult = jbossCli.cmd(command)
            if (!addResult.success) {
                throw new Exception("Failed to create server identity. ${addResult.response.toString()}")
            }
        }
    })

    retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
        @Override
        CLI.Result doWithRetry(RetryContext context) throws Exception {
            println("Attempt ${context.retryCount + 1} to set the https listener.")

            def existsResult = jbossCli.cmd("/subsystem=undertow/server=default-server/https-listener=https:read-resource")
            if (!existsResult.success) {
                def realmResult = jbossCli.cmd("/subsystem=undertow/server=default-server/https-listener=https/:add(" +
                        "socket-binding=https, " +
                        "security-realm=${OCTOPUS_SSL_REALM})")
                if (!realmResult.success) {
                    throw new Exception("Failed to set the https realm. ${realmResult.response.toString()}")
                }
            } else {
                def bindingResult = jbossCli.cmd("/subsystem=undertow/server=default-server/https-listener=https/:write-attribute(" +
                        "name=socket-binding, " + "" +
                        "value=https)")
                if (!bindingResult.success) {
                    throw new Exception("Failed to set the socket binding. ${bindingResult.response.toString()}")
                }
                def realmResult = jbossCli.cmd("/subsystem=undertow/server=default-server/https-listener=https/:write-attribute(" +
                        "name=security-realm, " +
                        "value=${OCTOPUS_SSL_REALM})")
                if (!realmResult.success) {
                    throw new Exception("Failed to set the security realm realm. ${realmResult.response.toString()}")
                }

            }
        }
    })

    /*
        Restart the server
     */
    retryTemplate.execute(new RetryCallback<CLI.Result, Exception>() {
        @Override
        CLI.Result doWithRetry(RetryContext context) throws Exception {
            println("Attempt ${context.retryCount + 1} to restart server.")

            def restartResult = jbossCli.cmd("/:shutdown(restart=true)")
            if (!restartResult.success) {
                throw new Exception("Failed to restart the server. ${restartResult.response.toString()}")
            }
        }
    })
}

/*
    Exiting like this can have consequences if the code is run from a Java app. But
    for an Octopus Deploy script it is ok, and is actually necessary to properly exit
    the script.
 */
System.exit(0)