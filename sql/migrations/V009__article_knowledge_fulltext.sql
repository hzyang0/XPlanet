-- Minimal in-place knowledge index: article remains the single source of truth.
USE xplanet;

ALTER TABLE `article`
    ADD FULLTEXT KEY `ft_article_knowledge` (`title`, `content`) WITH PARSER ngram;
