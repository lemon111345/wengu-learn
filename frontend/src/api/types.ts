// 多个接口共用的类型，对应后端的枚举

/** 知识点类型，对应后端的 KnowledgePointType */
export type KnowledgePointType =
  | 'CONCEPT'
  | 'COMPARISON'
  | 'MECHANISM'
  | 'ENUMERATION'
  | 'TROUBLESHOOTING'
  | 'SYSTEM_DESIGN'
  | 'EXPERIENCE'

/** 评分，对应后端的 Rating */
export type Rating = 'AGAIN' | 'HARD' | 'GOOD' | 'EASY'
