package com.llm.okf.mcp.repository;

import com.llm.okf.mcp.model.ModelDetailDoc;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ModelDetailRepository extends MongoRepository<ModelDetailDoc, String> {
}
