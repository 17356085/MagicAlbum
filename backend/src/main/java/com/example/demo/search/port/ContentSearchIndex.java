package com.example.demo.search.port;

import com.example.demo.threads.dto.ThreadSearchDocument;

import java.util.List;
import java.util.Optional;

/**
 * Search-index port used by the thread projection and its rebuild process.
 */
public interface ContentSearchIndex {
    String ALIAS = "tongpin-threads";
    String PREFIX = "tongpin-threads-v";

    record SearchHit(long id, long version) {
    }

    record SearchPage(List<SearchHit> hits, long total) {
        public SearchPage {
            hits = List.copyOf(hits);
        }
    }

    void create(String index);

    void put(String index, ThreadSearchDocument document);

    SearchPage search(String index, String q, String tag, Long sectionId, int page, int size);

    Optional<ThreadSearchDocument> read(String index, long id);

    long count(String index);

    void refresh(String index);

    Optional<String> activeIndex();

    void switchAlias(String newIndex);
}
