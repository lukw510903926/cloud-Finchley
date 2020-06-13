package com.cloud.common.util.mybatis;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.NestedIOException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author : lukew
 * @project : IDEA
 * @createTime : 2018/4/14 20:49
 * @email : 13507615840@163.com
 * @gitHub : https://github.com/lukw510903926
 * @description :
 */
@Slf4j
public class XmlMapperLoader implements DisposableBean, InitializingBean {

    private SqlSessionFactory sqlSessionFactory;

    /**
     * *Mapper.xml 文件路径
     */
    private String basePackage = "classpath*:**/*Mapper.xml";

    private final HashMap<String, String> fileMapping = new HashMap<>();

    private ScheduledExecutorService scheduledThreadPool;

    public void setBasePackage(String basePackage) {
        this.basePackage = basePackage;
    }

    public void setSqlSessionFactory(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }


    /**
     * @see InitializingBean 启动后设置属性值
     */
    @Override
    public void afterPropertiesSet() {

        scheduledThreadPool = new ScheduledThreadPoolExecutor(1);
        scheduledThreadPool.scheduleAtFixedRate(new Task(new Scanner()), 5, 15, TimeUnit.SECONDS);
    }

    class Task implements Runnable {

        private final Scanner scanner;

        public Task(Scanner scanner) {
            this.scanner = scanner;
        }

        @Override
        public void run() {
            try {
                List<Resource> resources = scanner.getChangedFiles();
                if (resources.size() > 0) {
                    log.debug("**********Mapper.xml文件改变,重新加载**************");
                    scanner.reloadXML(resources);
                    log.debug("*****************加载完毕***********************");
                }
            } catch (Exception e) {
                log.error("mapper文件修改重新加载失败: ", e);
            }
        }

    }

    class Scanner {

        private final String[] basePackages;

        public Scanner() {
            basePackages = StringUtils.tokenizeToStringArray(XmlMapperLoader.this.basePackage,
                    ConfigurableApplicationContext.CONFIG_LOCATION_DELIMITERS);
            try {
                scan();
            } catch (IOException e) {
                log.error("mapper文件扫描失败: ", e);
            }
        }

        /**
         * 获取mapper.xml 资源
         *
         * @param basePackage
         * @return
         * @throws IOException
         */
        public Resource[] getResource(String basePackage) throws IOException {

            return new PathMatchingResourcePatternResolver().getResources(basePackage);
        }

        /**
         * 重新加载xml文件
         *
         * @param resources
         * @throws Exception
         */
        public void reloadXML(List<Resource> resources) throws Exception {

            Configuration configuration = sqlSessionFactory.getConfiguration();
            removeConfig(configuration);
            for (Resource resource : resources) {
                try {
                    XMLMapperBuilder xmlMapperBuilder = new XMLMapperBuilder(resource.getInputStream(), configuration,
                            resource.toString(), configuration.getSqlFragments());
                    xmlMapperBuilder.parse();
                } catch (Exception e) {
                    throw new NestedIOException("Failed to parse mapping resource: '" + resource + "'", e);
                } finally {
                    ErrorContext.instance().reset();
                }
            }
        }

        /**
         * 清除配置
         *
         * @param configuration
         * @throws Exception
         */
        private void removeConfig(Configuration configuration) throws Exception {

            clearValues(configuration, "mappedStatements");
            clearValues(configuration, "caches");
            clearValues(configuration, "resultMaps");
            clearValues(configuration, "parameterMaps");
            clearValues(configuration, "keyGenerators");
            clearValues(configuration, "sqlFragments");
            // 清理已加载的资源标识，方便让它重新加载。
            Field loadedResourcesField = configuration.getClass().getDeclaredField("loadedResources");
            loadedResourcesField.setAccessible(true);
            ((Set<?>) loadedResourcesField.get(configuration)).clear();
        }

        private void clearValues(Configuration configuration, String fieldName) throws Exception {

            Field field = configuration.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Map<?, ?> map = (Map<?, ?>) field.get(configuration);
            StrictMap<Object> newMap = new StrictMap<Object>();
            for (Object key : map.keySet()) {
                try {
                    newMap.put((String) key, map.get(key));
                } catch (IllegalArgumentException ex) {
                    newMap.put((String) key, ex.getMessage());
                }
            }
            field.set(configuration, newMap);
        }

        public void scan() throws IOException {

            if (!fileMapping.isEmpty()) {
                return;
            }
            for (String basePackage : basePackages) {
                Resource[] resources = getResource(basePackage);
                if (resources != null) {
                    for (Resource resource : resources) {
                        fileMapping.put(resource.getFilename(), getValue(resource));
                    }
                }
            }
        }

        private String getValue(Resource resource) throws IOException {

            String contentLength = String.valueOf(resource.contentLength());
            String lastModified = String.valueOf(resource.lastModified());
            return contentLength + lastModified;
        }

        public List<Resource> getChangedFiles() throws IOException {
            List<Resource> list = new ArrayList<>();
            for (String basePackage : basePackages) {
                log.debug("开始 刷新mybatis mapper.xml 文件************************");
                Resource[] resources = getResource(basePackage);
                if (resources != null) {
                    for (Resource resource : resources) {
                        String name = resource.getFilename();
                        String value = fileMapping.get(name);
                        String multi_key = getValue(resource);
                        if (!multi_key.equals(value)) {
                            list.add(resource);
                            fileMapping.put(name, multi_key);
                            log.debug("{} 文件修改************************", name);
                        }
                    }
                }
            }
            log.debug("mybatis mapper.xml 文件刷新结束************************");
            return list;
        }
    }

    /**
     * @throws Exception
     * @see DisposableBean 销毁时执行
     */
    @Override
    public void destroy() {

        if (scheduledThreadPool != null) {
            scheduledThreadPool.shutdownNow();
        }
    }

    /**
     * 重写 org.apache.ibatis.session.Configuration.StrictMap 类 来自 MyBatis3.4.0版本，修改
     * put 方法，允许反复 put更新。
     */
    public static class StrictMap<V> extends HashMap<String, V> {

        private static final long serialVersionUID = -4950446264854982944L;

        @Override
        public V put(String key, V value) {
            remove(key);
            if (key.contains(".")) {
                final String shortKey = getShortName(key);
                if (super.get(shortKey) == null) {
                    super.put(shortKey, value);
                } else {
                    super.put(shortKey, (V) new Ambiguity(shortKey));
                }
            }
            return super.put(key, value);
        }

        private String getShortName(String key) {
            final String[] keyParts = key.split("\\.");
            return keyParts[keyParts.length - 1];
        }

        protected static class Ambiguity {

            private final String subject;

            public Ambiguity(String subject) {
                this.subject = subject;
            }

            public String getSubject() {
                return subject;
            }
        }
    }
}
