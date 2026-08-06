-- Repair reports created while offline corpus hits were incorrectly labelled as internal articles.
USE xplanet;

UPDATE evidence_chunk e
JOIN source_document s ON s.id = e.source_id
SET e.locator = '离线内置语料'
WHERE e.locator = 'published internal article'
  AND s.title LIKE '站内知识：%'
  AND s.url IN (
      'https://github.com/hzyang0/XPlanet',
      'https://microservices.io/patterns/data/transactional-outbox.html',
      'https://docs.langchain.com/oss/python/langgraph/quickstart',
      'https://redis.io/docs/latest/develop/data-types/streams/',
      'https://owasp.org/www-community/attacks/Server_Side_Request_Forgery'
  );

UPDATE source_document
SET title = CONCAT('离线语料：', SUBSTRING(title, CHAR_LENGTH('站内知识：') + 1)),
    metadata_json = JSON_SET(
        COALESCE(metadata_json, JSON_OBJECT()),
        '$.evidenceType',
        'offline-corpus'
    )
WHERE title LIKE '站内知识：%'
  AND url IN (
      'https://github.com/hzyang0/XPlanet',
      'https://microservices.io/patterns/data/transactional-outbox.html',
      'https://docs.langchain.com/oss/python/langgraph/quickstart',
      'https://redis.io/docs/latest/develop/data-types/streams/',
      'https://owasp.org/www-community/attacks/Server_Side_Request_Forgery'
  );

UPDATE ai_report r
JOIN ai_task t ON t.id = r.task_id
SET r.content = REPLACE(r.content, '[站内知识：', '[离线语料：')
WHERE t.provider = 'offline-demo'
  AND r.content LIKE '%[站内知识：%';
