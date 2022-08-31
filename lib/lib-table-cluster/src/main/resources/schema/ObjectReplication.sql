CREATE TABLE IF NOT EXISTS OBJECT_REPLICATION (
    OBJECT_TYPE ENUM('SUBMISSION', 'ENTITY') NOT NULL,
    OBJECT_ID BIGINT NOT NULL,
    OBJECT_VERSION BIGINT NOT NULL,
    CURRENT_VERSION BIGINT NOT NULL,
    CREATED_BY BIGINT NOT NULL,
    CREATED_ON BIGINT NOT NULL,
    ETAG CHAR(36) NOT NULL,
    NAME VARCHAR(256)CHARACTER SET UTF8MB4 COLLATE UTF8MB4_0900_AI_CI NOT NULL,
    SUBTYPE ENUM('dockerrepo','entityview','file','folder','link','project','submission','submissionview','table','dataset', 'datasetcollection', 'materializedview' ) NOT NULL,
    PARENT_ID BIGINT DEFAULT NULL,
    BENEFACTOR_ID BIGINT NOT NULL,
    PROJECT_ID BIGINT DEFAULT NULL,
    MODIFIED_BY BIGINT NOT NULL,
    MODIFIED_ON BIGINT NOT NULL,
    FILE_ID BIGINT DEFAULT NULL,
    FILE_SIZE_BYTES BIGINT DEFAULT NULL,
    IN_SYNAPSE_STORAGE BOOLEAN DEFAULT NULL,
    FILE_MD5 VARCHAR(100)CHARACTER SET UTF8MB4 COLLATE UTF8MB4_0900_AI_CI DEFAULT NULL,
    FILE_CONCRETE_TYPE VARCHAR(65)CHARACTER SET UTF8MB4 COLLATE UTF8MB4_0900_AI_CI DEFAULT NULL,
    FILE_BUCKET VARCHAR(100)CHARACTER SET UTF8MB4 COLLATE UTF8MB4_0900_AI_CI DEFAULT NULL,
    FILE_KEY VARCHAR(700)CHARACTER SET UTF8MB4 COLLATE UTF8MB4_0900_AI_CI DEFAULT NULL,
    PRIMARY KEY (OBJECT_TYPE, OBJECT_ID , OBJECT_VERSION),
    INDEX (CURRENT_VERSION),
    INDEX (CREATED_BY),
    INDEX (CREATED_ON),
    INDEX (ETAG),
    INDEX (NAME),
    INDEX (SUBTYPE),
    INDEX (PARENT_ID),
    INDEX (BENEFACTOR_ID),
    INDEX (PROJECT_ID),
    INDEX (MODIFIED_BY),
    INDEX (MODIFIED_ON)
)