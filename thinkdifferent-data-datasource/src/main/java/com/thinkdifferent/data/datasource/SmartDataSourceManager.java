package com.thinkdifferent.data.datasource;

import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.db.Entity;
import cn.hutool.json.JSONObject;
import com.thinkdifferent.data.DataHandlerManager;
import com.thinkdifferent.data.bean.*;
import com.thinkdifferent.data.csvLog.OpenCsvLog;
import com.thinkdifferent.data.datasource.remote.DefaultRemoteSource;
import com.thinkdifferent.data.datasource.remote.RemoteSource;
import com.thinkdifferent.data.datasource.sdb.SdbDataSourceEnum;
import com.thinkdifferent.data.extend.OneTableExtend;
import com.thinkdifferent.data.process.DataHandlerEntity;
import com.thinkdifferent.data.process.DataHandlerType;
import com.thinkdifferent.data.util.StringUtil;
import org.apache.commons.lang3.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据源管理类
 */
public class SmartDataSourceManager {
    /**
     * 数据源缓存
     * key: taskName_fromName | taskName_toName
     * value: dataSource
     */
    static Map<String, SmartDataSource> DATA_SOURCE_MAP = new HashMap<>();

    /**
     * 加载 数据库连接
     *
     * @param taskDo 单个任务， 单个配置文件
     */
    public static void loadDataSource(TaskDo taskDo) {
        final FromDo from = taskDo.getFrom();
        final ToDo to = taskDo.getTo();
        if (Boolean.FALSE.equals(from.getBlnRestReceive())) {
            DATA_SOURCE_MAP.put(StringUtil.join(taskDo.getName(), from.getName())
                    , SdbDataSourceEnum.findByProperties(from.getDataSourceProperties()));
        }else {
            DATA_SOURCE_MAP.put(StringUtil.join(taskDo.getName(), from.getName())
                    , new DefaultRemoteSource().initDataSource(from.getDataSourceProperties()));
        }
        DATA_SOURCE_MAP.put(StringUtil.join(taskDo.getName(), to.getName())
                , SdbDataSourceEnum.findByProperties(to.getDataSourceProperties()));
    }

    public static SmartDataSource getByName(String taskName, String sourceName) {
        String mapKey = StringUtil.join(taskName, sourceName);
        SmartDataSource smartDataSource = DATA_SOURCE_MAP.get(mapKey);
        if (Objects.isNull(smartDataSource)) {
            throw new RuntimeException("数据源" + mapKey + "不存在");
        }
        return smartDataSource;
    }

    /**
     * 收集及存储单表数据
     *
     * @param oneTableExtend 单表对象
     */
    public static void collectAndSaveData(OneTableExtend oneTableExtend) {
        if (Boolean.FALSE.equals(oneTableExtend.getFrom().getBlnRestReceive())) {
            // 数据库操作
            SmartDataSource fromDataSource = DATA_SOURCE_MAP.get(StringUtil.join(oneTableExtend.getName(), oneTableExtend.getFrom().getName()));
            SmartDataSource toDataSource = DATA_SOURCE_MAP.get(StringUtil.join(oneTableExtend.getName(), oneTableExtend.getTo().getName()));
            // 获取增量条件
            String incrementalCondition = toDataSource.getIncrementalCondition(oneTableExtend);
            int i = 1;
            do {
                // 查询数据
                List<Map<String, Object>> entitiesList = fromDataSource.listEntity(oneTableExtend, incrementalCondition);
                if (entitiesList.isEmpty()){
                    ThreadUtil.sleep(1000);
                    continue;
                }
                // 保存
                toDataSource.saveEntity(oneTableExtend, entitiesList);
                // 保存后操作
                toDataSource.afterSave(oneTableExtend);
                OpenCsvLog.info(oneTableExtend.getName(), "任务【{}】-【{}】第【{}】次执行成功"
                        , oneTableExtend.getName(), oneTableExtend.getTable().getName(), i++);
            } while (fromDataSource.next());
        }
    }

    public static void checkAndSaveData(OneTableExtend oneTableExtend, List<Map<String, Object>> entitiesList){
        SmartDataSource fromDataSource = DATA_SOURCE_MAP.get(StringUtil.join(oneTableExtend.getName(), oneTableExtend.getFrom().getName()));
        if (fromDataSource instanceof RemoteSource){
            SmartDataSource toDataSource = DATA_SOURCE_MAP.get(StringUtil.join(oneTableExtend.getName(), oneTableExtend.getTo().getName()));

            // 数据转换，将推送字段转换为数据库字段
            entitiesList = processData(oneTableExtend.getTable(), entitiesList);

            // 保存
            toDataSource.saveEntity(oneTableExtend, entitiesList);
            // 保存后操作
            toDataSource.afterSave(oneTableExtend);
            OpenCsvLog.info(oneTableExtend.getName(), "任务【{}】-【{}】接收外部数据执行成功"
                    , oneTableExtend.getName(), oneTableExtend.getTable().getName());
        }else {
            // TODO
            throw new RuntimeException("");
        }
    }

    /**
     * 数据加工处理
     *
     * @param table    table对象
     * @param dataList 原始数据
     * @return 处理后数据
     */
    private static List<Map<String, Object>> processData(TableDo table, List<Map<String, Object>> dataList) {
        return dataList.stream()
                .map(map -> {
                    Entity entity = new Entity(table.getName());
                    // from 中存在的字段
                    map.forEach((fromKey, fromValue) -> {
                        FieldDo fieldFind = table.getFields().stream().filter(fieldDo ->
                                // 字段名一致 或 “ fieldName” 结尾， 兼容 as
                                StringUtils.equals(fieldDo.getName(), fromKey)
                                        || StringUtils.endsWith(fieldDo.getName(), " " + fromKey))
                                .findFirst().orElse(null);
                        if (Objects.isNull(fieldFind)) {
                            throw new NullPointerException(
                                    StrUtil.format("error field name, table-field:{}-{}", table.getTargetName(), fromKey));
                        }

                        // 将字段从前往后依次加工处理
                        entity.set(fieldFind.getTargetName(), DataHandlerManager.handlerAndParse(
                                new DataHandlerEntity(DataHandlerType.getRespType(fieldFind.getHandleType())
                                        , fieldFind.getHandleExpress()
                                        , String.valueOf(fromValue)
                                        , new JSONObject(map)), fieldFind.getTargetType()));
                    });
                    // to 表中设置的默认字段, 字段内容为空，根据表达式计算
                    table.getFields().stream().filter(fieldDo -> StringUtils.isBlank(fieldDo.getName())).collect(Collectors.toList())
                            .forEach(fieldDo -> entity.set(fieldDo.getTargetName(), DataHandlerManager.handlerAndParse(
                                    new DataHandlerEntity(DataHandlerType.getRespType(fieldDo.getHandleType())
                                            , fieldDo.getHandleExpress()
                                            , fieldDo.getHandleExpress()
                                            , new JSONObject(map)), fieldDo.getTargetType())));
                    return entity;
                }).collect(Collectors.toList());
    }

    public static void close() {
        DATA_SOURCE_MAP.values().forEach(SmartDataSource::close);
    }

    public static void close(TaskDo taskDo) {
        DATA_SOURCE_MAP.get(StringUtil.join(taskDo.getName(), taskDo.getFrom().getName())).close();
        DATA_SOURCE_MAP.get(StringUtil.join(taskDo.getName(), taskDo.getTo().getName())).close();
    }
}
