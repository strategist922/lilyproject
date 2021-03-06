/*
 * Copyright 2010 Outerthought bvba
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lilyproject.rest;

import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.apache.commons.lang.BooleanUtils;
import org.lilyproject.repository.api.RecordType;
import org.lilyproject.repository.api.RecordTypeNotFoundException;
import org.lilyproject.repository.api.SchemaId;
import org.lilyproject.tools.import_.core.IdentificationMode;
import org.lilyproject.tools.import_.core.ImportMode;
import org.lilyproject.tools.import_.core.ImportResult;
import org.lilyproject.tools.import_.core.ImportResultType;
import org.lilyproject.tools.import_.core.RecordTypeImport;

@Path("schema/recordTypeById/{id}")
public class RecordTypeByIdResource extends TypeManagerEnabled {

    @GET
    @Produces("application/json")
    public Entity<RecordType> get(@PathParam("id") String id, @Context UriInfo uriInfo) {
        try {
            SchemaId schemaId = idGenerator.getSchemaId(id);
            return Entity.create(typeManager.getRecordTypeById(schemaId, null), uriInfo);
        } catch (RecordTypeNotFoundException e) {
            throw new ResourceException(e, NOT_FOUND.getStatusCode());
        } catch (Exception e) {
            throw new ResourceException("Error loading record type with id " + id, e, INTERNAL_SERVER_ERROR.getStatusCode());
        }
    }

    @PUT
    @Produces("application/json")
    @Consumes("application/json")
    public Response put(@PathParam("id") String id, RecordType recordType, @Context UriInfo uriInfo) {
        SchemaId schemaId = idGenerator.getSchemaId(id);

        if (recordType.getId() != null && !recordType.getId().equals(schemaId)) {
            throw new ResourceException("ID in submitted record type does not match the id in URI.",
                    BAD_REQUEST.getStatusCode());
        }
        recordType.setId(schemaId);

        boolean refreshSubtypes = BooleanUtils.toBoolean(uriInfo.getQueryParameters().getFirst("refreshSubtypes"));

        ImportResult<RecordType> result;
        try {
            result = RecordTypeImport.importRecordType(recordType, ImportMode.UPDATE, IdentificationMode.ID,
                    null, refreshSubtypes, typeManager);
        } catch (Exception e) {
            throw new ResourceException("Error creating or updating record type with id " + id, e,
                    INTERNAL_SERVER_ERROR.getStatusCode());
        }

        recordType = result.getEntity();
        Response response;

        ImportResultType resultType = result.getResultType();
        switch (resultType) {
            case UPDATED:
            case UP_TO_DATE:
                response = Response.ok(Entity.create(recordType, uriInfo)).build();
                break;
            case CANNOT_UPDATE_DOES_NOT_EXIST:
                throw new ResourceException("Record type not found: " + id, NOT_FOUND.getStatusCode());
            default:
                throw new RuntimeException("Unexpected import result type: " + resultType);
        }

        return response;
    }

}
