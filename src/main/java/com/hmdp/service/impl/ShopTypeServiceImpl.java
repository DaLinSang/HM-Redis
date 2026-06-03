package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        //1.从redis查询商铺类型缓存
        String shopTypeJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);
        //2.判断是否存在
        if(StrUtil.isNotBlank(shopTypeJson)){
            //3.存在，则返回
            log.debug("走缓存");
            List<ShopType> shopType = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(shopType);
        }
        //4.不存在，则查询数据库返回商铺类型信息
        List<ShopType> shopType = this.query().orderByAsc("sort").list();
        log.debug("走数据库");
        //5.不存在，返回错误
        if (shopType == null){
            return Result.fail("店铺类型不存在");
        }
        //6.存在，把商铺类型信息写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(shopType));
        //7.返回
        return Result.ok(shopType);
    }
}
