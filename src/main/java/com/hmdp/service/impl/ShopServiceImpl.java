package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //使用互斥锁解决缓存击穿问题
    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id)

        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿
        Shop shop = queryWithLogicalExpire(id);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        //7.返回
        return Result.ok(shop);
    }


    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //使用逻辑过期解决缓存击穿，在原来缓存穿透代码上进行封装
    public Shop queryWithLogicalExpire(Long id) {
        //1.从redis查询商铺缓存,拿到的是json数据
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //3.不存在，直接返回null
            return null;
        }
        //4.命中，先把JSON反序列化对象,并且拿到过期时间
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.需要判断缓存是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //5.1未过期，直接返回店铺信息
            return shop;
        }
        //5.2已过期，需要缓存重建
        //6.缓存重建
        //6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //6.2判断是否获取锁成功
        if (isLock){
            // 6.3成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //重建缓存
                    this.saveShop2Redis(id,20L);

                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        //6.4返回过期的商铺信息
        return shop;
    }


    //使用互斥锁解决缓存击穿，基于原来的缓存穿透的代码进行封装
    public Shop queryWithMutex(Long id){
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中的是否是空值
        if (shopJson != null){
            //返回空值
            return null;
        }
        //4.实现缓存重建
        //4.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2判断获取是否成功
            if (!isLock){
                //4.3如果失败，则休眠 并 重试
                Thread.sleep(50);
                //重试是指重新从Redis里面查询商铺缓存，也就是重新来一遍该函数，进行递归
                queryWithMutex(id);
            }
            //4.4如果获取锁成功，此时有可能是别的线程刚好释放锁，这个线程刚好拿到锁，有可能已经查过数据库房间redis了。
            // 所以要再次检查redis缓存是否存在，做doublecheck，如果存在则无需重建缓存
            //1.从redis查询商铺缓存
            String shopDoubleCheckJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            //2.判断是否存在
            if (StrUtil.isNotBlank(shopDoubleCheckJson)) {
                //3.存在，直接返回
                return JSONUtil.toBean(shopDoubleCheckJson, Shop.class);
            }
            // 如果发现确实本线程是第一个线程的话，则需要根据id查询数据库
            shop = getById(id);
            //模拟重建延时
            Thread.sleep(200);

            //5.不存在，返回错误
            if (shop == null){
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6.存在,数据写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //7.释放互斥锁
            unLock(lockKey);
        }
        //8.返回
        return shop;
    }

    //解决缓存穿透的代码
    public Shop queryWithPassThrough(Long id) {
        //1.从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否是空值
        if (shopJson != null){
            //返回错误信息
            return null;
        }
        //4.不存在，根据id查询数据库
        Shop shop = getById(id);
        //5.不存在，返回错误
        if (shop == null){
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.存在,数据写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //7.返回
        return shop;
    }

    private boolean tryLock(String key){
        //获取互斥锁，如果成功则flag为true，失败为false
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(LOCK_SHOP_KEY + key, "1", LOCK_SHOP_TTL, TimeUnit.MINUTES);
        //返回获取是否成功
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }


    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //1.查询店铺数据
        Shop shop = getById(id);
        //模拟缓存重建的延时
        Thread.sleep(200);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));

    }


    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
