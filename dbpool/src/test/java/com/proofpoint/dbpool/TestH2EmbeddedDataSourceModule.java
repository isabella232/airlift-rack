package com.proofpoint.dbpool;

import com.google.inject.Binder;
import com.google.inject.BindingAnnotation;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.ProvisionException;
import com.proofpoint.configuration.ConfigurationFactory;
import com.proofpoint.configuration.ConfigurationModule;
import com.proofpoint.testing.Assertions;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.management.MBeanServer;
import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.HashMap;
import java.util.Map;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotSame;
import static org.testng.AssertJUnit.assertSame;


public class TestH2EmbeddedDataSourceModule
{
    @Retention(RUNTIME)
    @Target({FIELD, PARAMETER, METHOD})
    @BindingAnnotation
    public @interface MainBinding
    {
    }

    @Retention(RUNTIME)
    @Target({FIELD, PARAMETER, METHOD})
    @BindingAnnotation
    public @interface AliasBinding
    {
    }

    public static class ObjectHolder
    {
        public final DataSource dataSource;

        @Inject
        public ObjectHolder(@MainBinding DataSource dataSource)
        {
            this.dataSource = dataSource;
        }
    }

    public static class TwoObjectsHolder
    {
        public final DataSource mainDataSource;
        public final DataSource aliasedDataSource;

        @Inject
        public TwoObjectsHolder(@MainBinding DataSource mainDataSource, @AliasBinding DataSource aliasedDataSource)
        {
            this.mainDataSource = mainDataSource;
            this.aliasedDataSource = aliasedDataSource;
        }
    }

    @Test(expectedExceptions = NullPointerException.class)
    public void testNullPrefixInConstructionThrows()
    {
        H2EmbeddedDataSourceModule notActuallyConstructed = new H2EmbeddedDataSourceModule(null, MainBinding.class);
    }

    @Test(expectedExceptions = NullPointerException.class)
    public void testNullAnnotationInConstructionThrows()
    {
        H2EmbeddedDataSourceModule notActuallyConstructed = new H2EmbeddedDataSourceModule("test", null);
    }

    @Test
    public void testInjectorConstruction()
    {
        Map<String, String> properties = new HashMap<String, String>();

        // Properties aren't actually used until we try to instantiate an H2EmbeddedDataSource
        Injector injector = createModuleInjectorWithProperties(new H2EmbeddedDataSourceModule("test", MainBinding.class), properties);
    }

    @Test(groups = "requiresTempFile")
    public void testObjectBindingFromInjector()
    {
        Map<String, String> properties = createDefaultConfigurationProperties(temporaryFile.getAbsolutePath());

            Injector injector = createModuleInjectorWithProperties(new H2EmbeddedDataSourceModule("", MainBinding.class), properties);

            ObjectHolder objectHolder = injector.getInstance(ObjectHolder.class);

        Assertions.assertInstanceof(objectHolder.dataSource, H2EmbeddedDataSource.class);
    }

    @Test(groups = "requiresTempFile")
    public void testBoundObjectIsASingleton()
    {
        Map<String, String> properties = createDefaultConfigurationProperties(temporaryFile.getAbsolutePath());

        Injector injector = createInjector(new H2EmbeddedDataSourceModule("", MainBinding.class), properties);

            Injector injector = createModuleInjectorWithProperties(new H2EmbeddedDataSourceModule("", MainBinding.class), properties);

            ObjectHolder objectHolder1 = injector.getInstance(ObjectHolder.class);
            ObjectHolder objectHolder2 = injector.getInstance(ObjectHolder.class);

        // But held data source objects should be the same
        assertSame(objectHolder1.dataSource, objectHolder2.dataSource);
    }

    @Test(groups = "requiresTempFile")
    public void testAliasedBindingBindsCorrectly()
    {
        Map<String, String> properties = createDefaultConfigurationProperties(temporaryFile.getAbsolutePath());

            Injector injector = createModuleInjectorWithProperties(new H2EmbeddedDataSourceModule("", MainBinding.class, AliasBinding.class), properties);

            ObjectHolder objectHolder = injector.getInstance(ObjectHolder.class);
            TwoObjectsHolder twoObjectsHolder = injector.getInstance(TwoObjectsHolder.class);

            // Held data source objects should all be of the correct type
            assert twoObjectsHolder.mainDataSource instanceof H2EmbeddedDataSource;
            assert twoObjectsHolder.aliasedDataSource instanceof H2EmbeddedDataSource;

        // And should all be references to the same object
        assertSame(objectHolder.dataSource, twoObjectsHolder.mainDataSource);
        assertSame(objectHolder.dataSource, twoObjectsHolder.aliasedDataSource);
    }

    @Test(groups = "requiresTempFile")
    public void testCorrectConfigurationPrefix()
    {
        final String expectedPrefix = "expected";
        final String otherPrefix = "additional";

        final String propertySuffixToTest = ".db.connections.max";
        final int expectedValue = 1234;

        // Required properties for construction
        Map<String, String> properties = createDefaultConfigurationPropertiesWithPrefix(expectedPrefix, temporaryFile.getAbsolutePath());

        // Optional property added with two different prefixes, two different values
        properties.put(otherPrefix + propertySuffixToTest, Integer.toString(expectedValue + 5678));
        properties.put(expectedPrefix + propertySuffixToTest, Integer.toString(expectedValue));

            Injector injector = createModuleInjectorWithProperties(new H2EmbeddedDataSourceModule(expectedPrefix, MainBinding.class), properties);

            ObjectHolder objectHolder = injector.getInstance(ObjectHolder.class);

            // Make sure we picked up the value with the expected prefix
            assert objectHolder.dataSource instanceof H2EmbeddedDataSource : "Expected H2EmbeddedDataSource to be injected";

            H2EmbeddedDataSource created = (H2EmbeddedDataSource) objectHolder.dataSource;

        assertEquals(created.getMaxConnections(), expectedValue, "Property value not loaded from correct prefix");
    }

    @Test(groups = "requiresTempFile", expectedExceptions = ProvisionException.class)
    public void testIncorrectConfigurationPrefixThrows()
    {
        final String configurationPrefix = "configuration";
        final String constructionPrefix = "differentFromConfiguration";

        Map<String, String> properties = createDefaultConfigurationPropertiesWithPrefix(configurationPrefix, temporaryFile.getAbsolutePath());

        Injector injector = createModuleInjectorWithProperties(new H2EmbeddedDataSourceModule(constructionPrefix, MainBinding.class), properties);

    }

    private static Injector createModuleInjectorWithProperties(H2EmbeddedDataSourceModule module, Map<String, String> properties)
    {
        ConfigurationFactory configurationFactory = new ConfigurationFactory(properties);

        // Required to bind a configuration module and an MBean server when binding an H2EmbeddedDataSourceModule
        Injector injector = Guice.createInjector(module,
                new ConfigurationModule(configurationFactory),
                new Module() {
                    @Override
                    public void configure(Binder binder) {
                        binder.bind(MBeanServer.class).toInstance(mock(MBeanServer.class));
                    }
                });

        return injector;
    }


    // Any test that actually instantiates an H2EmbeddedDataSource requires a temporary file to use for the database
    private File temporaryFile;

    @BeforeMethod(groups = "requiresTempFile")
    private void createTempFile()
            throws IOException {
        this.temporaryFile = File.createTempFile("h2db-", ".db");
    }

    @AfterMethod(groups = "requiresTempFile")
    private void deleteTempFile() {
        this.temporaryFile.delete();
    }

    private static Map<String, String> createDefaultConfigurationProperties(String filename) {
        return createDefaultConfigurationPropertiesWithPrefix("", filename);
    }

    private static Map<String, String> createDefaultConfigurationPropertiesWithPrefix(String prefix, String filename) {
        Map<String, String> properties = new HashMap<String, String>();

        if (prefix.length() > 0 && prefix.charAt(prefix.length() - 1) != '.') {
            prefix = prefix + ".";
        }

        properties.put(prefix + "db.filename", filename);
        properties.put(prefix + "db.init-script", "com/proofpoint/dbpool/h2.ddl");
        properties.put(prefix + "db.cipher", "AES");
        properties.put(prefix + "db.file-password", "filePassword");
        return properties;
    }
}
