USE xplanet;

UPDATE evidence_chunk
SET locator = CASE locator
    WHEN 'fetched document' THEN '已抓取原文'
    WHEN 'published internal article' THEN '站内已发布文章'
    WHEN 'web search snippet' THEN '网页搜索摘要'
    ELSE locator
END
WHERE locator IN ('fetched document', 'published internal article', 'web search snippet');
