-- 获取锁中的线程标识
-- 判断是否与当前线程标识一致
if(redis.call('get',KEYS[1])==ARGV[1]) then
    -- 一致，释放锁
    redis.call('del',KEYS[1])
end
-- 不一致，返回
return 0