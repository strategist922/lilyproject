package org.lilyproject.indexer.model.indexerconf;

import java.io.IOException;
import java.util.Set;

import com.google.common.collect.Sets;
import org.lilyproject.repository.api.IdGenerator;
import org.lilyproject.repository.api.Record;
import org.lilyproject.repository.api.RecordNotFoundException;
import org.lilyproject.repository.api.Repository;
import org.lilyproject.repository.api.RepositoryException;
import org.lilyproject.repository.api.VersionNotFoundException;
import org.lilyproject.util.repo.VersionTag;

/**
 * Represents a -prop1[,-prop2 ...] follow
 */
public class VariantFollow implements Follow {
    private Set<String> dimensions;

    public VariantFollow(Set<String> dimensions) {
        this.dimensions = dimensions;
    }

    public Set<String> getDimensions() {
        return dimensions;
    }

    @Override
    public void follow(IndexUpdateBuilder indexUpdateBuilder, FollowCallback callback)
            throws RepositoryException, IOException, InterruptedException {
        Repository repository = indexUpdateBuilder.getRepositoryManager().getRepository(indexUpdateBuilder.getTable());
        IdGenerator idGenerator = repository.getIdGenerator();
        RecordContext ctx = indexUpdateBuilder.getRecordContext();

        Set<String> currentDimensions = Sets.newHashSet(ctx.dep.id.getVariantProperties().keySet());
        currentDimensions.addAll(ctx.dep.moreDimensionedVariants);

        if (!currentDimensions.containsAll(dimensions)) {
            // the current dimension doesn't contain all the dimensions we need to subtract -> stop here
            return;
        }
        Dep newDep = ctx.dep.minus(idGenerator, dimensions);

        Record lessDimensionedRecord = null;
        try {
            lessDimensionedRecord = VersionTag.getIdRecord(newDep.id, indexUpdateBuilder.getVTag(), repository);
        } catch (RecordNotFoundException e) {
            // It's ok that the variant does not exist
        } catch (VersionNotFoundException e) {
            // It's ok that the variant does not exist
        }

        indexUpdateBuilder.push(lessDimensionedRecord, newDep);
        callback.call();
        indexUpdateBuilder.pop();
    }
}