CREATE TABLE IF NOT EXISTS `SUBSTATUS_STRINGANNOTATION` (
  `ID` BIGINT NOT NULL AUTO_INCREMENT,
  `ATTRIBUTE` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `SUBMISSION_ID` BIGINT NOT NULL,
  `VALUE` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `IS_PRIVATE` boolean,
  PRIMARY KEY (`ID`),
  KEY `VAL1_ATT2_INDEX` (`VALUE`,`ATTRIBUTE`),
  KEY `VAL1_OWN2_INDEX` (`VALUE`,`SUBMISSION_ID`),
  KEY `OWN1_ATT2_INDEX` (`SUBMISSION_ID`,`ATTRIBUTE`),
  KEY `OWN1_VAL2_INDEX` (`SUBMISSION_ID`,`VALUE`),
  KEY `ATT1_VAL1_INDEX` (`ATTRIBUTE`,`VALUE`),
  KEY `JDOSTRINGANNOTATION_N49` (`SUBMISSION_ID`),
  UNIQUE KEY `STRING_ANNO_UNIQUE` (`SUBMISSION_ID`,`ATTRIBUTE`),
  KEY `ATT1_OWN2_INDEX` (`ATTRIBUTE`,`SUBMISSION_ID`),
  FOREIGN KEY (`SUBMISSION_ID`) REFERENCES `SUBSTATUS_ANNOTATIONS_OWNER` (`SUBMISSION_ID`) ON DELETE CASCADE
)
