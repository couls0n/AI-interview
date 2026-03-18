@echo off
echo ========================================
echo 安装 pgvector 扩展到 PostgreSQL
echo ========================================
echo.

echo 复制 vector.dll ...
copy /Y "D:\pgvector-0.8.0\pgvector-0.8.0\vector.dll" "D:\PostgreSQL\lib\"

echo 复制 vector--0.8.0.sql ...
copy /Y "D:\pgvector-0.8.0\pgvector-0.8.0\sql\vector--0.8.0.sql" "D:\PostgreSQL\share\extension\"

echo 创建 vector.control ...
echo # pgvector extension > "D:\PostgreSQL\share\extension\vector.control"
echo comment = 'vector data type and ivfflat and hnsw access methods' >> "D:\PostgreSQL\share\extension\vector.control"
echo default_version = '0.8.0' >> "D:\PostgreSQL\share\extension\vector.control"
echo module_pathname = '$libdir/vector' >> "D:\PostgreSQL\share\extension\vector.control"
echo relocatable = false >> "D:\PostgreSQL\share\extension\vector.control"

echo.
echo ========================================
echo 安装完成！
echo ========================================
echo.
echo 现在启动 PostgreSQL 服务...
net start postgresql-x64-16

echo.
echo 请按任意键退出...
pause
