## Configuration Details


|FieldName|Format                     |Description|Sources|
|---      |---                        |---        |---    |
|         |[all-of](fielddescriptions)|           |       |

### Field Descriptions

|FieldName                             |Format                         |Description|Sources|
|---                                   |---                            |---        |---    |
|[tripping-strategy](tripping-strategy)|[any-one-of](tripping-strategy)|           |       |
|[reset-schedule](reset-schedule)      |[all-of](reset-schedule)       |           |       |

### tripping-strategy

|FieldName   |Format                       |Description      |Sources|
|---         |---                          |---              |---    |
|max-failures|primitive                    |value of type int|       |
|            |[all-of](fielddescriptions-1)|                 |       |

### Field Descriptions

|FieldName             |Format   |Description                                |Sources|
|---                   |---      |---                                        |---    |
|failure-rate-threshold|primitive|value of type double                       |       |
|sample-duration       |primitive|value of type duration, default value: PT1M|       |
|min-throughput        |primitive|value of type int, default value: 10       |       |
|nr-sample-buckets     |primitive|value of type int, default value: 10       |       |

### reset-schedule

|FieldName|Format   |Description                                |Sources|
|---      |---      |---                                        |---    |
|min      |primitive|value of type duration, default value: PT1S|       |
|max      |primitive|value of type duration, default value: PT1M|       |
|factor   |primitive|value of type double, default value: 2.0   |       |

