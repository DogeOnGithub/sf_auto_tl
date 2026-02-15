-- 版本表增加 Mod 文件原始文件名
ALTER TABLE creation_version ADD COLUMN file_name VARCHAR(255);
