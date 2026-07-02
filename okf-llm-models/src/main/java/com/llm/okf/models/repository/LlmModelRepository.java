package com.llm.okf.models.repository;

import com.llm.okf.models.model.LlmModelDoc;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface LlmModelRepository extends MongoRepository<LlmModelDoc, String> {
}
