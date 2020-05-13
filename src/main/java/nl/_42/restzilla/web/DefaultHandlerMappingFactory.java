package nl._42.restzilla.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.CharStreams;
import io.beanmapper.BeanMapper;
import nl._42.restzilla.RestConfig;
import nl._42.restzilla.registry.CrudServiceRegistry;
import nl._42.restzilla.service.CrudService;
import nl._42.restzilla.service.Lazy;
import nl._42.restzilla.service.ReadService;
import nl._42.restzilla.web.mapping.BeanMapperAdapter;
import nl._42.restzilla.web.mapping.Mapper;
import nl._42.restzilla.web.query.CrudServiceListable;
import nl._42.restzilla.web.query.Listable;
import nl._42.restzilla.web.query.MappingListable;
import nl._42.restzilla.web.query.ReadServiceListable;
import nl._42.restzilla.web.security.SecurityProvider;
import nl._42.restzilla.web.util.JsonUtil;
import nl._42.restzilla.web.util.PageableResolver;
import nl._42.restzilla.web.util.UrlUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.convert.ConversionService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Persistable;
import org.springframework.data.domain.Sort;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.Validator;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.method.HandlerMethod;

import javax.servlet.http.HttpServletRequest;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.springframework.web.bind.annotation.RequestMethod.DELETE;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.PATCH;
import static org.springframework.web.bind.annotation.RequestMethod.POST;
import static org.springframework.web.bind.annotation.RequestMethod.PUT;

/**
 * Default implementation of the {@link ResourceHandlerMappingFactory}.
 *
 * @author Jeroen van Schagen
 * @since Aug 21, 2015
 */
public class DefaultHandlerMappingFactory implements ResourceHandlerMappingFactory {
    
    private static final String ARRAY_JSON_START = "[";

    private final ObjectMapper objectMapper;
    
    private final ConversionService conversionService;
    
    private final BeanMapper beanMapper;

    private final SecurityProvider securityProvider;
    
    private final Validator validator;
    
    private ReadService readService;

    private CrudServiceRegistry crudServiceRegistry;

    /**
     * Instantiate a new {@link DefaultHandlerMappingFactory}.
     * 
     * @param objectMapper
     *              the {@link ObjectMapper} for JSON parsing and formatting
     * @param conversionService
     *              the {@link ConversionService} for converting between types
     * @param beanMapper
     *              the {@link BeanMapper} for mapping between beans
     * @param securityProvider
     *              the {@link SecurityProvider} checking the authorization
     * @param validator
     *              the {@link Validator} for verifying the input
     */
    public DefaultHandlerMappingFactory(ObjectMapper objectMapper,
                                        ConversionService conversionService,
                                        BeanMapper beanMapper,
                                        SecurityProvider securityProvider,
                                        Validator validator) {
        this.objectMapper = objectMapper;
        this.conversionService = conversionService;
        this.beanMapper = beanMapper;
        this.securityProvider = securityProvider;
        this.validator = validator;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DefaultHandlerMapping build(Class<?> resourceType) {
        RestInformation information = new RestInformation(resourceType);
        return new DefaultHandlerMapping(new DefaultCrudController(information));
    }
    
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private class DefaultCrudController {
        
        private final RestInformation information;
        private final Mapper mapper;

        /**
         * Always access this field by {@code getEntityService()} as it is initialized lazy.
         */
        private CrudService entityService;
        
        public DefaultCrudController(RestInformation information) {
            this.information = information;
            this.mapper = new BeanMapperAdapter(beanMapper, information);
        }
        
        //
        // Read queries
        //

        /**
         * Retrieve all entities.
         * 
         * @return the entities, in result type
         */
        @ResponseBody
        public Object findAll(HttpServletRequest request) {
            ensureIsReadable(information.findAll().secured(), request);

            Listable<?> listable = buildListable();
            Sort sort = PageableResolver.getSort(request, listable.getEntityClass());
            if (information.isPagedOnly() || PageableResolver.isSupported(request)) {
                Pageable pageable = PageableResolver.getPageable(request, sort);
                return listable.findAll(pageable);
            } else {
                return listable.findAll(sort);
            }
        }

        private void ensureIsReadable(String[] expressions, HttpServletRequest request) {
            if (!hasAnyNotBlank(expressions)) {
                expressions = information.getReadSecured();
            }
            ensureIsAuthorized(expressions, request);
        }

        private boolean hasAnyNotBlank(String[] expressions) {
            boolean result = false;
            for (String expression : expressions) {
                if (StringUtils.isNotBlank(expression)) {
                    result = true;
                }
            }
            return result;
        }

        private void ensureIsAuthorized(String[] expressions, HttpServletRequest request) {
            if (!securityProvider.isAuthorized(expressions, request)) {
                throw new SecurityException("Not authorized, should be one of: " + StringUtils.join(expressions, ", "));
            }
        }

        private Listable<?> buildListable() {
            ResultInformation findAll = information.getResultInfo(information.findAll());
            Class<?> resultType = findAll.getResultType();

            Listable<?> delegate = new CrudServiceListable(entityService, information.getEntityClass());
            if (information.hasCustomQuery(findAll)) {
                delegate = new ReadServiceListable(readService, findAll.getQueryType());
            }
            return new MappingListable(delegate, mapper, resultType);
        }

        /**
         * Retrieve a single entity by identifier: /{id}
         * 
         * @param request the request
         * @return the entity, in result type
         */
        @ResponseBody
        public Object findOne(HttpServletRequest request) {
            ensureIsReadable(information.findOne().secured(), request);
            return mapIdToResult(extractId(request));
        }
        
        private Serializable extractId(HttpServletRequest request) {
            String path = UrlUtils.getPath(request);
            String raw = StringUtils.substringAfterLast(path, UrlUtils.SLASH);
            return conversionService.convert(raw, information.getIdentifierClass().asSubclass(Serializable.class));
        }

        private Object mapIdToResult(Serializable id) {
            ResultInformation findOne = information.getResultInfo(information.findOne());
            Object entity;
            if (information.hasCustomQuery(findOne)) {
                entity = readService.getOne((Class) findOne.getQueryType(), id);
            } else {
                entity = entityService.getOne(id);
            }
            return mapper.map(entity, findOne.getResultType());
        }

        //
        // Modifications
        //

        /**
         * Saves an entity. Any content is retrieved from the request body.
         * 
         * @return the entity
         */
        @ResponseBody
        public Object create(HttpServletRequest request) throws Exception {
            ensureIsModifiable(information.create().secured(), request);
            String json = CharStreams.toString(request.getReader()).trim();
            Class<?> inputType = information.getInputType(information.create());

            if (json.startsWith(ARRAY_JSON_START)) {
                List<Object> inputs = objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, inputType));
                List<Object> results = new ArrayList<>();
                for (Object input : inputs) {
                    results.add(doCreate(input));
                }
                return results;
            } else {
                Object input = objectMapper.readValue(json, inputType);
                return doCreate(input);
            }
        }
        
        private void ensureIsModifiable(String[] expressions, HttpServletRequest request) {
            if (!hasAnyNotBlank(expressions)) {
                expressions = information.getModifySecured();
            }
            ensureIsAuthorized(expressions, request);
        }
        
        private Object doCreate(Object input) throws BindException {
            Persistable<?> entity = mapper.map(validate(input), information.getEntityClass());
            Persistable<?> output = entityService.save(entity);
            return mapToResult(output, information.create());
        }

        private Object validate(Object input) throws BindException {
            BeanPropertyBindingResult errors = new BeanPropertyBindingResult(input, "input");
            validator.validate(input, errors);
            if (errors.hasErrors()) {
                throw new BindException(errors);
            }
            return input;
        }

        /**
         * Updates an entity. Any content is retrieved from the request body.
         * 
         * @return the updated entity
         */
        @ResponseBody
        public Object update(HttpServletRequest request) throws Exception {
            ensureIsModifiable(information.update().secured(), request);
            Serializable id = extractId(request);
            String json = CharStreams.toString(request.getReader());
            Object input = objectMapper.readValue(json, information.getInputType(information.update()));
            boolean patch = request.getMethod().equals(PATCH.name());
            return doUpdate(id, json, validate(input), patch);
        }
        
        private Object doUpdate(Serializable id, String json, Object input, boolean patch) {
            Persistable<?> saved = entityService.save(new Updater(id, input, json, patch));
            return mapToResult(saved, information.update());
        }

        /**
         * Deletes an entity based on an identifier: /{id}
         * 
         * @return the deleted entity
         */
        @ResponseBody
        public Object delete(HttpServletRequest request) {
            ensureIsModifiable(information.delete().secured(), request);
            Persistable<?> entity = entityService.getOne(extractId(request));
            entityService.delete(entity);
            return mapToResult(entity, information.delete());
        }
        
        /**
         * Convert the entity into our desired result type.
         * 
         * @param entity the entity
         * @param config the configuration
         * @return the entity in its result type
         */
        private <T extends Persistable<ID>, ID extends Serializable> Object mapToResult(Persistable<?> entity, RestConfig config) {
            ResultInformation resultInfo = information.getResultInfo(config);

            Object result = entity;
            if (information.hasCustomQuery(resultInfo)) {
                final ID id = ((Persistable<ID>) entity).getId();
                result = readService.getOne((Class) resultInfo.getQueryType(), id);
            }

            Class<?> resultType = resultInfo.getResultType();
            return mapper.map(result, resultType);
        }

        private void init() {
            if (entityService == null) {
                entityService = crudServiceRegistry.getService((Class) information.getEntityClass());
            }
        }

        /**
         * Maps our input into the persisted entity on demand.
         * This mapping is performed lazy to ensure that the
         * entity is not auto-updated between transactions.
         *
         * @author Jeroen van Schagen
         * @since Nov 13, 2015
         */
        private class Updater implements Lazy<Object> {

            private final Serializable id;

            private final Object input;

            private final String json;

            private final boolean patch;

            public Updater(Serializable id, Object input, String json, boolean patch) {
                this.id = id;
                this.input = input;
                this.json = json;
                this.patch = patch;
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public Object get() {
                Persistable<?> entity = entityService.getOne(id);
                if (patch) {
                    Set<String> propertyNames = JsonUtil.getPropertyNamesFromJson(json, objectMapper);
                    beanMapper.wrapConfig()
                                .downsizeSource(new ArrayList<>(propertyNames))
                                .build()
                                .map(input, entity);
                } else {
                    beanMapper.map(input, entity);
                }
                return entity;
            }

        }

    }

    /**
     * Maps our requests to controller handle methods.
     *
     * @author Jeroen van Schagen
     * @since Sep 3, 2015
     */
    private static class DefaultHandlerMapping extends ResourceHandlerMapping {
        
        private static final String FIND_ALL_NAME = "findAll";
        private static final String FIND_ONE_NAME = "findOne";
        private static final String CREATE_NAME = "create";
        private static final String UPDATE_NAME = "update";
        private static final String DELETE_NAME = "delete";

        private final DefaultCrudController controller;
        
        public DefaultHandlerMapping(DefaultCrudController controller) {
            super(controller.information);
            this.controller = controller;
        }
        
        /**
         * {@inheritDoc}
         */
        @Override
        public Object getHandlerInternal(HttpServletRequest request) throws Exception {
            controller.init(); // Lazy initialization

            Object result = null;
            Method method = findMethod(request);
            if (method != null) {
                result = new HandlerMethod(controller, method);
            }
            return result;
        }

        /**
         * Dynamically match our servlet request to one of the service
         * methods. Whenever a request is recognized we return that method.
         * 
         * @param request the incomming request
         * @return the matching method, if any
         */
        private Method findMethod(HttpServletRequest request) throws NoSuchMethodException {
            if (getInformation().isReadOnly() && !hasRequestMethod(request, GET)) {
                return null;
            }

            Method method = null;
            String path = StringUtils.stripEnd(UrlUtils.getPath(request), "/");
            String[] fragments = path.split(UrlUtils.SLASH);
            if (fragments.length == 1) {
                if (hasRequestMethod(request, GET)) {
                    method = toMethodIfEnabled(FIND_ALL_NAME, getInformation().findAll());
                } else if (hasRequestMethod(request, POST)) {
                    method = toMethodIfEnabled(CREATE_NAME, getInformation().create());
                }
            } else if (fragments.length == 2) {
                String id = fragments[1];
                if (StringUtils.isNumeric(id)) {
                    if (hasRequestMethod(request, GET)) {
                        method = toMethodIfEnabled(FIND_ONE_NAME, getInformation().findOne());
                    } else if (hasRequestMethod(request, PUT) || (hasRequestMethod(request, PATCH) && getInformation().isPatch())) {
                        method = toMethodIfEnabled(UPDATE_NAME, getInformation().update());
                    } else if (hasRequestMethod(request, DELETE)) {
                        method = toMethodIfEnabled(DELETE_NAME, getInformation().delete());
                    }
                }
            }
            return method;
        }

        private boolean hasRequestMethod(HttpServletRequest request, RequestMethod method) {
            return method.name().equals(request.getMethod());
        }

        private Method toMethodIfEnabled(String methodName, RestConfig config) throws NoSuchMethodException {
            Method method = null;
            if (config.enabled()) {
                method = controller.getClass().getMethod(methodName, HttpServletRequest.class);
            }
            return method;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void describe(Logger logger) {
            final String basePath = controller.information.getBasePath();
            
            logIf(controller.information.findAll(), logger, "Mapped \"[/{}],methods=[GET],params=[]\"", basePath);
            logIf(controller.information.findOne(), logger, "Mapped \"[/{}/{id}],methods=[GET],params=[]\"", basePath);
            
            if (!controller.information.isReadOnly()) {
                logIf(controller.information.create(), logger, "Mapped \"[/{}],methods=[POST],params=[]\"", basePath);
                logIf(controller.information.update(), logger, "Mapped \"[/{}/{id}],methods=[PUT],params=[]\"", basePath);
                logIf(controller.information.delete(), logger, "Mapped \"[/{}/{id}],methods=[DELETE],params=[]\"", basePath);
            }
        }
        
        private void logIf(RestConfig config, Logger logger, String msg, Object... args) {
            if (config.enabled()) {
                logger.info(msg, args);
            }
        }

    }

    @Autowired
    public void setReadService(ReadService readService) {
        this.readService = readService;
    }
    
    @Autowired
    public void setCrudServiceRegistry(CrudServiceRegistry crudServiceRegistry) {
        this.crudServiceRegistry = crudServiceRegistry;
    }

}
