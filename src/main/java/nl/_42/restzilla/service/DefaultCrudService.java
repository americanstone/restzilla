/*
 * (C) 2014 42 bv (www.42.nl). All rights reserved.
 */
package nl._42.restzilla.service;

import nl._42.restzilla.registry.EntityClassAware;
import org.springframework.cache.Cache;
import org.springframework.cache.support.NoOpCache;
import org.springframework.core.GenericTypeResolver;
import org.springframework.data.domain.Persistable;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

/**
 * Default implementation of the CRUD service, delegates all to the repository.
 *
 * @author Jeroen van Schagen
 * @since Aug 21, 2015
 */
public class DefaultCrudService<T extends Persistable<ID>, ID extends Serializable> extends AbstractCrudService<T, ID> implements EntityClassAware<T> {

    private final Class<T> entityClass;

    /**
     * Cache used internally for storing entities.
     */
    private Cache cache = new NoOpCache("empty");

    /**
     * Construct a new service.
     */
    public DefaultCrudService() {
        this.entityClass = (Class<T>) GenericTypeResolver.resolveTypeArguments(getClass(), CrudService.class)[0];
    }

    /**
     * Construct a new service.
     * @param entityClass the entity class
     */
    public DefaultCrudService(Class<T> entityClass) {
        requireNonNull(entityClass, "Entity class cannot be null");
        this.entityClass = entityClass;
    }

    /**
     * Construct a new service.
     *
     * @param repository the repository
     */
    public DefaultCrudService(PagingAndSortingRepository<T, ID> repository) {
        this();
        setRepository(repository);
    }

    /**
     * Construct a new service.
     * 
     * @param entityClass the entity class
     * @param repository the repository
     */
    public DefaultCrudService(Class<T> entityClass, PagingAndSortingRepository<T, ID> repository) {
        this(entityClass);
        setRepository(repository);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(readOnly = true)
    public List<T> findAll() {
        return cache.get(
            "findAll()",
            super::findAll
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<T> find(final ID id) {
        return cache.get(
            format("find(%s)", id),
            () -> super.find(id)
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public <S extends T> S save(S entity) {
        S result = super.save(entity);
        cache.clear();
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void delete(T entity) {
        super.delete(entity);
        cache.clear();
    }

    /**
     * Retrieves the cache.
     * @return cache
     */
    protected Cache getCache() {
        return cache;
    }
    
    /**
     * Modifies the cache.
     * @param cache the new cache
     */
    protected void setCache(Cache cache) {
        this.cache = cache;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<T> getEntityClass() {
        return entityClass;
    }

}
